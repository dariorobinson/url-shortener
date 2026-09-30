package com.schwab.urlshortener.api.dto;

import com.schwab.urlshortener.api.ShortUrlLinks;
import com.schwab.urlshortener.service.ShortUrlView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The short URL resource, shared by create (US-006) and details (US-007) (D58). All eight fields are
 * always present; {@code lastAccessedAt} is {@code null} until the first click. There is no
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
        Instant lastAccessedAt) {

    public static ShortUrlResponse from(ShortUrlView view, ShortUrlLinks links) {
        return new ShortUrlResponse(view.shortCode(), links.publicUrl(view.shortCode()), view.originalUrl(),
                view.status().name(), view.customAlias(), view.clickCount(), view.createdAt(),
                view.lastAccessedAt());
    }
}
