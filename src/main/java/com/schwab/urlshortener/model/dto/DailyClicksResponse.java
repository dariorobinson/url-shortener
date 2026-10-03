package com.schwab.urlshortener.model.dto;

import com.schwab.urlshortener.model.DailyClicks;
import java.time.LocalDate;

/**
 * One entry of the stats {@code daily} series (D101): a local date and its click count.
 *
 * @param date the local calendar date in the requested time zone, {@code yyyy-MM-dd}
 * @param clicks clicks on that local date; 0 when there were none
 */
public record DailyClicksResponse(LocalDate date, long clicks) {

    public static DailyClicksResponse from(DailyClicks daily) {
        return new DailyClicksResponse(daily.date(), daily.clicks());
    }
}
