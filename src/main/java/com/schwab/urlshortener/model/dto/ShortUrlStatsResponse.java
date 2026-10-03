package com.schwab.urlshortener.model.dto;

import com.schwab.urlshortener.model.ShortUrlStats;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The stats resource (D101). All ten fields are always present; {@code lastAccessedAt} is {@code null} until the
 * first click. {@code timezone}, {@code from} and {@code to} are the resolved values after defaults.
 *
 * @param shortCode the short code
 * @param timezone the time zone used for the daily buckets, as accepted; UTC when not supplied
 * @param from the first local date of the window, inclusive, after defaults
 * @param to the last local date of the window, inclusive, after defaults
 * @param totalClicks the all-time number of clicks (the same value as {@code clickCount} of the short URL resource)
 * @param clicksInRange the clicks inside the window: the sum of {@code daily[].clicks}
 * @param lastAccessedAt the time of the latest click, ISO-8601 UTC (an instant has no zone), or null before the
 *        first click
 * @param daily one entry per local date in [from, to], ascending, including days with no clicks
 * @param expiresAt when the short URL expires, ISO-8601 UTC, or null if it never expires
 * @param expired true if the short URL had expired at the time of this request
 */
public record ShortUrlStatsResponse(String shortCode, String timezone, LocalDate from, LocalDate to,
        long totalClicks, long clicksInRange, Instant lastAccessedAt, List<DailyClicksResponse> daily,
        Instant expiresAt, boolean expired) {

    public static ShortUrlStatsResponse from(ShortUrlStats stats) {
        return new ShortUrlStatsResponse(stats.shortCode(), stats.zoneId(), stats.from(), stats.to(),
                stats.totalClicks(), stats.clicksInRange(), stats.lastAccessedAt(),
                stats.daily().stream().map(DailyClicksResponse::from).toList(), stats.expiresAt(), stats.expired());
    }
}
