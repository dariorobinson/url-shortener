package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.util.analytics.ClickRecorder;
import com.schwab.urlshortener.util.domain.ShortUrl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** US-014 H13: each fail-open click loss increments {@code shortener.clicks.lost}; a recorded click does not. */
class RedirectServiceMetricsTest {

    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ClickRecorder clickRecorder = mock(ClickRecorder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RedirectService service;

    @BeforeEach
    void setUp() {
        service = new RedirectService(repository, transactionManager, clickRecorder,
                Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), meters);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ShortUrl url = ShortUrl.create("abc1234", "https://example.com/x", false, "alice",
                Instant.parse("2026-09-01T00:00:00Z"));
        ReflectionTestUtils.setField(url, "id", 7L);
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(url));
    }

    private double lost() {
        return meters.get(RedirectService.LOST_CLICKS_METRIC).counter().count();
    }

    @Test
    void shouldRegisterTheCounterAtZero() {
        assertThat(lost()).isZero();
    }

    @Test
    void shouldCountEveryLostClickAndNoRecordedOne() {
        service.resolveAndRecordClick("abc1234");
        assertThat(lost()).isZero();

        doThrow(new QueryTimeoutException("slow")).when(clickRecorder).record(anyLong(), any());
        service.resolveAndRecordClick("abc1234");
        service.resolveAndRecordClick("abc1234");

        assertThat(lost()).isEqualTo(2.0);
    }
}
