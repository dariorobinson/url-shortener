package com.schwab.urlshortener.model;

import java.time.LocalDate;

/** The click count of one local calendar date in the caller's zone (D19, D101). */
public record DailyClicks(LocalDate date, long clicks) {
}
