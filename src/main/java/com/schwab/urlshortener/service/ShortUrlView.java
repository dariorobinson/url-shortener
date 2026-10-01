package com.schwab.urlshortener.service;

import com.schwab.urlshortener.util.domain.ShortUrl;
import com.schwab.urlshortener.util.domain.ShortUrlStatus;
import java.time.Instant;

/**
 * What the service layer returns instead of the entity (entities never leave it). {@code createdBy} is
 * deliberately absent (D58). It holds the URL, so it must never be logged whole.
 *
 * <p>{@code expiresAt} is null for a link that never expires; {@code expired} is computed at the instant of the
 * request that produced the view (D111, D118).
 */
public record ShortUrlView(String shortCode, String originalUrl, ShortUrlStatus status, boolean customAlias,
        long clickCount, Instant createdAt, Instant lastAccessedAt, Instant expiresAt, boolean expired) {

    /** A view of a link that never expires. */
    public ShortUrlView(String shortCode, String originalUrl, ShortUrlStatus status, boolean customAlias,
            long clickCount, Instant createdAt, Instant lastAccessedAt) {
        this(shortCode, originalUrl, status, customAlias, clickCount, createdAt, lastAccessedAt, null, false);
    }

    /** @param now the request's instant, read once from the injected clock (D45) */
    public static ShortUrlView from(ShortUrl url, Instant now) {
        return new ShortUrlView(url.getShortCode(), url.getOriginalUrl(), url.getStatus(), url.isCustomAlias(),
                url.getClickCount(), url.getCreatedAt(), url.getLastAccessedAt(), url.getExpiresAt(),
                url.isExpiredAt(now));
    }
}
