package com.schwab.urlshortener.util.analytics;

import com.schwab.urlshortener.entity.ClickEvent;
import com.schwab.urlshortener.repository.ClickEventRepository;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a click with the atomic counter UPDATE (D16, D27) and the event INSERT in one {@code REQUIRES_NEW}
 * transaction, so either failure rolls back both. The UPDATE runs first and is guarded by {@code status = 'ACTIVE'}
 * (D91): only if it changed a row is the event inserted. Both statements use the same instant, truncated to
 * microseconds here rather than rounded by the database (D45). Never swallows failures: the caller fails open
 * (D12, D93). Never annotate this class with {@code @Transactional}; the template is not published as a bean,
 * which would replace Boot's default {@code transactionTemplate}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaClickRecorder implements ClickRecorder {

    private final ShortUrlRepository shortUrls;
    private final ClickEventRepository events;
    private final PlatformTransactionManager transactionManager;

    /** Its own transaction, so a recording failure never touches the redirect's read (D12, D93). */
    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Override
    public void record(long shortUrlId, Instant clickedAt) {
        Instant at = clickedAt.truncatedTo(ChronoUnit.MICROS);
        requiresNew().executeWithoutResult(status -> {
            if (shortUrls.recordClick(shortUrlId, at) == 1) {
                events.saveAndFlush(ClickEvent.of(shortUrlId, at));
            } else {
                log.debug("Click not recorded: id={} reason=NOT_ACTIVE", shortUrlId);
            }
        });
    }
}
