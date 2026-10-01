package com.schwab.urlshortener.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What the service layer returns for the stats endpoint instead of the entity (D101). {@code zoneId}, {@code from}
 * and {@code to} are the resolved values. {@code totalClicks} is the all-time {@code click_count};
 * {@code clicksInRange} is the sum of {@code daily}.
 */
public record ShortUrlStats(String shortCode, String zoneId, LocalDate from, LocalDate to, long totalClicks,
        long clicksInRange, Instant lastAccessedAt, List<DailyClicks> daily, Instant expiresAt, boolean expired) {

    public ShortUrlStats {
        daily = List.copyOf(daily);
    }

    /** Stats of a link that never expires. */
    public ShortUrlStats(String shortCode, String zoneId, LocalDate from, LocalDate to, long totalClicks,
            long clicksInRange, Instant lastAccessedAt, List<DailyClicks> daily) {
        this(shortCode, zoneId, from, to, totalClicks, clicksInRange, lastAccessedAt, daily, null, false);
    }

    /** {@code expiresAt} and {@code expired} are the link's expiry and whether it had passed at the request (D118). */
    public static ShortUrlStats of(String shortCode, StatsPeriod period, long totalClicks, Instant lastAccessedAt,
            List<DailyClicks> daily, Instant expiresAt, boolean expired) {
        long inRange = daily.stream().mapToLong(DailyClicks::clicks).sum();
        return new ShortUrlStats(shortCode, period.zoneId(), period.from(), period.to(), totalClicks, inRange,
                lastAccessedAt, daily, expiresAt, expired);
    }
}
