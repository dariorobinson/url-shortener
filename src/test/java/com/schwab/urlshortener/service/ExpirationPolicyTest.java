package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.schwab.urlshortener.config.ExpirationProperties;
import com.schwab.urlshortener.service.exception.InvalidExpirationException;
import java.time.Instant;
import java.time.Period;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** US-016 AC2: D107, D124, D127, checked against an explicit "now", never the system clock. */
class ExpirationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-09-30T12:00:00.123456Z");
    private static final Instant TEN_YEARS = Instant.parse("2036-09-30T12:00:00.123456789Z");

    private final ExpirationPolicy policy = new ExpirationPolicy(new ExpirationProperties(Period.ofYears(10)));

    @Test
    void shouldAcceptOneMicrosecondAfterNowAndReturnTheTruncatedValue() {
        assertThat(policy.validate(NOW_MICROS.plusNanos(1000), NOW)).isEqualTo(NOW_MICROS.plusNanos(1000));
        assertThat(policy.validate(NOW_MICROS.plusNanos(1999), NOW)).isEqualTo(NOW_MICROS.plusNanos(1000));
    }

    @ParameterizedTest
    @ValueSource(longs = {-86_400_000_000_000L, -1000L, 0L, 1L, 500L, 999L})
    void shouldRejectAnythingThatIsNotStrictlyLaterThanNowAfterTruncation(long nanosFromNowMicros) {
        // 500 ns after now passes a naive check, then equals created_at once truncated and would break the V3 CHECK.
        Instant candidate = NOW_MICROS.plusNanos(nanosFromNowMicros);

        assertThatThrownBy(() -> policy.validate(candidate, NOW)).isExactlyInstanceOf(InvalidExpirationException.class);
    }

    @Test
    void shouldAcceptExactlyTheHorizonAndRejectOneMicrosecondBeyondIt() {
        assertThat(policy.validate(TEN_YEARS, NOW)).isEqualTo(Instant.parse("2036-09-30T12:00:00.123456Z"));
        assertThatThrownBy(() -> policy.validate(TEN_YEARS.plusNanos(1000), NOW))
                .isExactlyInstanceOf(InvalidExpirationException.class);
    }

    @Test
    void shouldCarryTheRuleTextButNeverTheRejectedValue() {
        Instant rejected = Instant.parse("2001-02-03T04:05:06Z");

        InvalidExpirationException ex =
                (InvalidExpirationException) catchThrowable(() -> policy.validate(rejected, NOW));

        assertThat(ex.rule()).isEqualTo("must be in the future and at most 10 years ahead");
        assertThat(ex.getMessage()).doesNotContain("2001");
        assertThat(ex.rule()).doesNotContain("2001");
    }

    @Test
    void shouldDescribeANonYearHorizonAsAnIsoPeriod() {
        ExpirationPolicy monthly = new ExpirationPolicy(new ExpirationProperties(Period.ofMonths(1)));

        assertThat(monthly.rule()).isEqualTo("must be in the future and at most P1M ahead");
        assertThat(new ExpirationPolicy(new ExpirationProperties(Period.ofYears(1))).rule())
                .isEqualTo("must be in the future and at most 1 year ahead");
    }
}
