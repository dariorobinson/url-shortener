package com.schwab.urlshortener.controller.dto;

import com.schwab.urlshortener.service.ShortUrlStats;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The stats resource (D101). All ten fields are always present; {@code lastAccessedAt} is {@code null} until the
 * first click. {@code timezone}, {@code from} and {@code to} are the resolved values after defaults.
 */
public record ShortUrlStatsResponse(
        @Schema(description = "The short code", example = "aB3dE9x") String shortCode,
        @Schema(description = "The time zone used for the daily buckets, as accepted; UTC when not supplied",
                example = "America/New_York") String timezone,
        @Schema(description = "First local date of the window, inclusive, after defaults", type = "string",
                format = "date", example = "2026-03-07") LocalDate from,
        @Schema(description = "Last local date of the window, inclusive, after defaults", type = "string",
                format = "date", example = "2026-03-09") LocalDate to,
        @Schema(description = "All-time number of clicks (the same value as clickCount of the short URL resource)",
                example = "1234") long totalClicks,
        @Schema(description = "Clicks inside the window: the sum of daily[].clicks", example = "6") long clicksInRange,
        @Schema(description = "Time of the latest click, ISO-8601 UTC (an instant has no zone), or null before the "
                + "first click", nullable = true) Instant lastAccessedAt,
        @Schema(description = "One entry per local date in [from, to], ascending, including days with no clicks")
        List<DailyClicksResponse> daily,
        @Schema(description = "When the short URL expires, ISO-8601 UTC, or null if it never expires",
                nullable = true) Instant expiresAt,
        @Schema(description = "True if the short URL had expired at the time of this request") boolean expired) {

    public static ShortUrlStatsResponse from(ShortUrlStats stats) {
        return new ShortUrlStatsResponse(stats.shortCode(), stats.zoneId(), stats.from(), stats.to(),
                stats.totalClicks(), stats.clicksInRange(), stats.lastAccessedAt(),
                stats.daily().stream().map(DailyClicksResponse::from).toList(), stats.expiresAt(), stats.expired());
    }
}
