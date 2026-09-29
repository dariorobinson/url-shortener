package com.schwab.urlshortener.domain;

/**
 * Lifecycle status of a {@link ShortUrl}. Persisted with {@code @Enumerated(STRING)}, so the
 * constant names must match {@code ck_short_url_status} exactly; renaming a constant is a schema
 * change.
 */
public enum ShortUrlStatus {
    ACTIVE,
    DEACTIVATED,
    DELETED
}
