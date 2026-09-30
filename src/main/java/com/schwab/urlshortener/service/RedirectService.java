package com.schwab.urlshortener.service;

import com.schwab.urlshortener.analytics.ClickRecorder;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.repository.PostgresServerErrors;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.shortcode.ShortCodeFormat;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a public short link (FR-2). Only ACTIVE links redirect; malformed (D72, D77), unknown, DEACTIVATED (D2)
 * and DELETED codes all throw the one {@link ShortUrlNotFoundException}. Never annotate this class with
 * {@code @Transactional}: the read runs in a read-only {@link TransactionTemplate} that has finished before
 * {@link #resolve} or {@link #resolveAndRecordClick} records anything, so click recording can never share it (D12).
 * The template is built here and deliberately not published as a bean, which would replace Boot's default
 * {@code transactionTemplate}.
 *
 * <p>Fail-open lives here, around the recorder call (D12, D93): any {@link RuntimeException} from recording is
 * logged and swallowed, so no {@link ClickRecorder} implementation can remove it.
 */
@Slf4j
@Service
public class RedirectService {

    private final ShortUrlRepository repository;
    private final TransactionTemplate readOnly;
    private final ClickRecorder clickRecorder;
    private final Clock clock;

    /** The resolved link: the id for the recorder and the stored target. The entity never leaves this class. */
    private record Resolved(long id, String target) {
    }

    public RedirectService(ShortUrlRepository repository, PlatformTransactionManager transactionManager,
            ClickRecorder clickRecorder, Clock clock) {
        this.repository = repository;
        this.clickRecorder = clickRecorder;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Resolves without recording a click: used for HEAD (D9, D18).
     *
     * @return the stored original URL of an ACTIVE link, exactly as stored
     * @throws ShortUrlNotFoundException if the code is malformed or unknown, or the link is DEACTIVATED or DELETED;
     *         the cases are indistinguishable (D2, D72, D74)
     */
    public String resolve(String code) {
        return lookup(code).target();
    }

    /**
     * Resolves, then records one click after the read transaction has closed: used for GET (D9). A recording
     * failure never reaches the caller (D12, D93). Only the code, id, exception class and SQLSTATE are logged:
     * never the exception object, its message, a stack trace or the URL.
     *
     * @return the stored original URL of an ACTIVE link, exactly as stored
     * @throws ShortUrlNotFoundException as {@link #resolve}; nothing is recorded then
     */
    public String resolveAndRecordClick(String code) {
        Resolved link = lookup(code);
        try {
            clickRecorder.record(link.id(), clock.instant());
        } catch (RuntimeException e) {
            log.warn("Click not recorded: code={} id={} exception={} sqlState={}", code, link.id(),
                    e.getClass().getSimpleName(), PostgresServerErrors.sqlState(e).orElse("none"));
        }
        return link.target();
    }

    private Resolved lookup(String code) {
        if (!ShortCodeFormat.isWellFormed(code)) {
            // D72, D77: cannot exist, so no transaction and no connection. The value is client text, never logged.
            log.debug("Redirect not found: reason=MALFORMED");
            throw new ShortUrlNotFoundException();
        }
        return readOnly.execute(status -> {
            ShortUrl url = repository.findByShortCode(code).orElse(null);   // case-sensitive (D6)
            String reason = null;
            if (url == null) {
                reason = "NOT_FOUND";
            } else if (url.getStatus() != ShortUrlStatus.ACTIVE) {
                reason = url.getStatus().name();
            }
            if (reason != null) {
                log.debug("Redirect not found: code={} reason={}", code, reason);
                throw new ShortUrlNotFoundException();
            }
            return new Resolved(url.getId(), url.getOriginalUrl());
        });
    }
}
