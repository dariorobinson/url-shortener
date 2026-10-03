package com.schwab.urlshortener.model.dto;

import com.schwab.urlshortener.model.ShortUrlView;
import com.schwab.urlshortener.util.link.ShortUrlLinks;
import java.time.Instant;

/**
 * The short URL resource, shared by create (US-006), details (US-007) and update (US-009) (D58). All ten fields are
 * always present; {@code lastAccessedAt} is {@code null} until the first click and {@code expiresAt} is {@code null}
 * for a link that never expires (D118). There is no
 * {@code createdBy}, {@code id}, {@code updatedAt} or {@code version}.
 *
 * @param shortCode the short code (Base62, 3 to 32 characters)
 * @param shortUrl the public short link, built from the configured base URL
 * @param originalUrl the original URL, exactly as submitted
 * @param status {@code ACTIVE} or {@code DEACTIVATED}
 * @param customAlias true when the code is a caller-chosen alias
 * @param clickCount the number of recorded clicks; 0 on create
 * @param createdAt the creation time, ISO-8601 UTC
 * @param lastAccessedAt the time of the last click, or null before the first click
 * @param expiresAt when the short URL expires, ISO-8601 UTC, or null if it never expires
 * @param expired true if the short URL had expired at the time of this request; its public redirect then returns
 *        410 {@code SHORT_URL_EXPIRED}
 */
public record ShortUrlResponse(String shortCode, String shortUrl, String originalUrl, String status,
        boolean customAlias, long clickCount, Instant createdAt, Instant lastAccessedAt, Instant expiresAt,
        boolean expired) {

    public static ShortUrlResponse from(ShortUrlView view, ShortUrlLinks links) {
        return new ShortUrlResponse(view.shortCode(), links.publicUrl(view.shortCode()), view.originalUrl(),
                view.status().name(), view.customAlias(), view.clickCount(), view.createdAt(),
                view.lastAccessedAt(), view.expiresAt(), view.expired());
    }
}
