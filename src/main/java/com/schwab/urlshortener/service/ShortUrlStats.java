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
        long clicksInRange, Instant lastAccessedAt, List<DailyClicks> daily) {

    public ShortUrlStats {
        daily = List.copyOf(daily);
    }

    public static ShortUrlStats of(String shortCode, StatsPeriod period, long totalClicks, Instant lastAccessedAt,
            List<DailyClicks> daily) {
        long inRange = daily.stream().mapToLong(DailyClicks::clicks).sum();
        return new ShortUrlStats(shortCode, period.zoneId(), period.from(), period.to(), totalClicks, inRange,
                lastAccessedAt, daily);
    }
}
