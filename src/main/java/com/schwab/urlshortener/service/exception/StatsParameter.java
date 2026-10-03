package com.schwab.urlshortener.service.exception;

import lombok.RequiredArgsConstructor;
/**
 * The query parameters of the stats endpoint. A violation names its parameter from this fixed set, never from client
 * text, so the rejected name or value cannot reach a response body or a log line (D99, D100).
 */
@RequiredArgsConstructor
public enum StatsParameter {
    TIMEZONE(StatsParameter.TIMEZONE_NAME),
    FROM(StatsParameter.FROM_NAME),
    TO(StatsParameter.TO_NAME);

    /** Compile-time constants for annotation attributes such as {@code @Parameter(name = ...)}. */
    public static final String TIMEZONE_NAME = "timezone";
    public static final String FROM_NAME = "from";
    public static final String TO_NAME = "to";

    private final String wireName;

    /** The exact, case-sensitive name on the wire. */
    public String wireName() {
        return wireName;
    }
}
