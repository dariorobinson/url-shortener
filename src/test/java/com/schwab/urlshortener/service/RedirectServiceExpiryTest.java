package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.ShortUrlExpiredException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.util.analytics.ClickRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** US-016: the redirect's expiry check (AC3, AC4, AC8; D109, D111, D113, D117, D119). */
class RedirectServiceExpiryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-09-30T12:00:00Z");
    private static final long LINK_ID = 42L;

    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ClickRecorder clickRecorder = mock(ClickRecorder.class);
    private final Clock clock = mock(Clock.class);
    private RedirectService service;

    @BeforeEach
    void setUp() {
        service = new RedirectService(repository, transactionManager, clickRecorder, clock, meters);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    private ShortUrl stored(String code, Instant expiresAt) {
        ShortUrl url = ShortUrl.create(code, "https://example.com/x", false, "alice", CREATED, expiresAt);
        ReflectionTestUtils.setField(url, "id", LINK_ID);
        when(repository.findByShortCode(code)).thenReturn(Optional.of(url));
        return url;
    }

    @Test
    void shouldRedirectAndRecordWithTheSameInstantOneMicrosecondBeforeExpiry() {
        stored("abc1234", EXPIRY);
        Instant justBefore = EXPIRY.minusNanos(1000);
        when(clock.instant()).thenReturn(justBefore);

        assertThat(service.resolveAndRecordClick("abc1234")).isEqualTo("https://example.com/x");

        verify(clickRecorder).record(LINK_ID, justBefore);
        verify(clock, times(1)).instant();
    }

    @Test
    void shouldThrowExpiredAtAndAfterTheExpiryAndNeverRecord() {
        stored("abc1234", EXPIRY);
        for (Instant now : new Instant[] {EXPIRY, EXPIRY.plusNanos(1000)}) {
            when(clock.instant()).thenReturn(now);

            assertThatThrownBy(() -> service.resolveAndRecordClick("abc1234"))
                    .isExactlyInstanceOf(ShortUrlExpiredException.class);
            assertThatThrownBy(() -> service.resolve("abc1234")).isExactlyInstanceOf(ShortUrlExpiredException.class);
        }
        verifyNoInteractions(clickRecorder);
    }

    @Test
    void shouldReturnNotFoundForADeactivatedOrDeletedLinkEvenIfItHasExpired() {
        stored("deact12", EXPIRY).deactivate(CREATED);
        stored("delet12", EXPIRY).softDelete("admin", CREATED);
        when(clock.instant()).thenReturn(EXPIRY.plusSeconds(60));

        assertThatThrownBy(() -> service.resolveAndRecordClick("deact12"))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.resolve("delet12")).isExactlyInstanceOf(ShortUrlNotFoundException.class);
        // Positive control: an ACTIVE link with the same expiry is gone, not missing.
        stored("activ12", EXPIRY);
        assertThatThrownBy(() -> service.resolve("activ12")).isExactlyInstanceOf(ShortUrlExpiredException.class);
    }

    @Test
    void shouldNeverExpireALinkWithoutAnExpiry() {
        stored("abc1234", null);
        when(clock.instant()).thenReturn(Instant.parse("2099-01-01T00:00:00Z"));

        assertThat(service.resolve("abc1234")).isEqualTo("https://example.com/x");
    }
}
