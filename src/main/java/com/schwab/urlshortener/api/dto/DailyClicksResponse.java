package com.schwab.urlshortener.api.dto;

import com.schwab.urlshortener.service.DailyClicks;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** One entry of the stats {@code daily} series (D101): a local date and its click count. */
public record DailyClicksResponse(
        @Schema(description = "The local calendar date in the requested time zone, yyyy-MM-dd",
                type = "string", format = "date", example = "2026-03-08") LocalDate date,
        @Schema(description = "Clicks on that local date; 0 when there were none", example = "4") long clicks) {

    public static DailyClicksResponse from(DailyClicks daily) {
        return new DailyClicksResponse(daily.date(), daily.clicks());
    }
}
