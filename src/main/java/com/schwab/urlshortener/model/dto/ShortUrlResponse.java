package com.schwab.urlshortener.model.dto;

import com.schwab.urlshortener.model.ShortUrlView;
import com.schwab.urlshortener.util.link.ShortUrlLinks;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The short URL resource, shared by create (US-006), details (US-007) and update (US-009) (D58). All ten fields are
 * always present; {@code lastAccessedAt} is {@code null} until the first click and {@code expiresAt} is {@code null}
 * for a link that never expires (D118). There is no
 * {@code createdBy}, {@code id}, {@code updatedAt} or {@code version}.
 */
public record ShortUrlResponse(
        @Schema(description = "The short code (Base62, 3 to 32 characters)", example = "aB3dE9x") String shortCode,
        @Schema(description = "The public short link, built from the configured base URL",
                example = "https://short.example/aB3dE9x") String shortUrl,
        @Schema(description = "The original URL, exactly as submitted") String originalUrl,
        @Schema(description = "ACTIVE or DEACTIVATED", allowableValues = {"ACTIVE", "DEACTIVATED"}) String status,
        @Schema(description = "True when the code is a caller-chosen alias") boolean customAlias,
        @Schema(description = "Number of recorded clicks; 0 on create") long clickCount,
        @Schema(description = "Creation time, ISO-8601 UTC") Instant createdAt,
        @Schema(description = "Time of the last click, or null before the first click", nullable = true)
        Instant lastAccessedAt,
        @Schema(description = "When the short URL expires, ISO-8601 UTC, or null if it never expires",
                nullable = true) Instant expiresAt,
        @Schema(description = "True if the short URL had expired at the time of this request; its public "
                + "redirect then returns 410 SHORT_URL_EXPIRED") boolean expired) {

    public static ShortUrlResponse from(ShortUrlView view, ShortUrlLinks links) {
        return new ShortUrlResponse(view.shortCode(), links.publicUrl(view.shortCode()), view.originalUrl(),
                view.status().name(), view.customAlias(), view.clickCount(), view.createdAt(),
                view.lastAccessedAt(), view.expiresAt(), view.expired());
    }
}
