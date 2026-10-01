package com.schwab.urlshortener.model;

import java.time.Instant;

/**
 * Input of {@link ShortUrlService#create}. A record so that the three strings cannot be passed in the
 * wrong order. It holds the URL and the username, so it must never be logged whole.
 *
 * @param originalUrl the URL exactly as submitted (D11, D47)
 * @param alias the custom alias, or null to have a code generated
 * @param createdBy the authenticated principal's configured username (D4, D54)
 * @param expiresAt the requested expiry, or null for a link that never expires (D106, D107)
 */
public record CreateShortUrlCommand(String originalUrl, String alias, String createdBy, Instant expiresAt) {

    /** A command for a link that never expires. */
    public CreateShortUrlCommand(String originalUrl, String alias, String createdBy) {
        this(originalUrl, alias, createdBy, null);
    }
}
