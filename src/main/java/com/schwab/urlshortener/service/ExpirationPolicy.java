package com.schwab.urlshortener.service;

import com.schwab.urlshortener.config.ExpirationProperties;
import com.schwab.urlshortener.service.exception.InvalidExpirationException;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates a requested {@code expiresAt} against the injected clock's "now" (D107, D124, D127). Deliberately not
 * Bean Validation's {@code @Future}, which reads the system clock and would bypass the injected {@code Clock} (D45).
 */
@Component
@RequiredArgsConstructor
public class ExpirationPolicy {

    private final ExpirationProperties properties;

    /**
     * The comparison is made on microsecond-truncated values, the precision PostgreSQL stores (D45): an
     * {@code expiresAt} 500 ns after {@code now} would otherwise pass here and then equal {@code created_at}, and the
     * CHECK {@code ck_short_url_expires_after_created} would turn a client error into a 500.
     *
     * @return {@code expiresAt} truncated to microseconds
     * @throws NullPointerException if either argument is null
     * @throws InvalidExpirationException if it is not strictly after {@code now}, or is beyond {@code now} plus the
     *         configured horizon
     */
    public Instant validate(Instant expiresAt, Instant now) {
        Instant expiry = expiresAt.truncatedTo(ChronoUnit.MICROS);
        Instant current = now.truncatedTo(ChronoUnit.MICROS);
        Instant latest = now.atOffset(ZoneOffset.UTC).plus(properties.maxHorizon()).toInstant();
        if (!expiry.isAfter(current) || expiry.isAfter(latest)) {
            throw new InvalidExpirationException(rule());
        }
        return expiry;
    }

    /** The rule text returned to clients in the {@code errors} extension; never contains the submitted value. */
    public String rule() {
        return "must be in the future and at most " + describe(properties.maxHorizon()) + " ahead";
    }

    private static String describe(Period period) {
        if (period.getMonths() == 0 && period.getDays() == 0) {
            return period.getYears() + (period.getYears() == 1 ? " year" : " years");
        }
        return period.toString();
    }
}
