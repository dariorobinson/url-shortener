package com.schwab.urlshortener.service;

import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.shortcode.ShortCodeFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves a public short link (FR-2). Only ACTIVE links redirect; malformed (D72, D77), unknown, DEACTIVATED (D2)
 * and DELETED codes all throw the one {@link ShortUrlNotFoundException}. Never annotate this class with
 * {@code @Transactional}: the read runs in a read-only {@link TransactionTemplate} that has finished before
 * {@link #resolve} returns, so click recording (US-010) can never share it (D12). The template is built here and
 * deliberately not published as a bean, which would replace Boot's default {@code transactionTemplate}.
 */
@Slf4j
@Service
public class RedirectService {

    private final ShortUrlRepository repository;
    private final TransactionTemplate readOnly;

    public RedirectService(ShortUrlRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @return the stored original URL of an ACTIVE link, exactly as stored
     * @throws ShortUrlNotFoundException if the code is malformed or unknown, or the link is DEACTIVATED or DELETED;
     *         the cases are indistinguishable (D2, D72, D74)
     */
    public String resolve(String code) {
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
            return url.getOriginalUrl();
        });
    }
}
