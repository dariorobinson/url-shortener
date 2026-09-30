package com.schwab.urlshortener.service;

import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import java.time.Instant;

/**
 * What the service layer returns instead of the entity (entities never leave it). {@code createdBy} is
 * deliberately absent (D58). It holds the URL, so it must never be logged whole.
 */
public record ShortUrlView(String shortCode, String originalUrl, ShortUrlStatus status, boolean customAlias,
        long clickCount, Instant createdAt, Instant lastAccessedAt) {

    public static ShortUrlView from(ShortUrl url) {
        return new ShortUrlView(url.getShortCode(), url.getOriginalUrl(), url.getStatus(), url.isCustomAlias(),
                url.getClickCount(), url.getCreatedAt(), url.getLastAccessedAt());
    }
}
