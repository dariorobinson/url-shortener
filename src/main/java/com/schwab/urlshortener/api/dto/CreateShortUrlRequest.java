package com.schwab.urlshortener.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/urls}. Bean Validation is structural only: every other rule belongs to
 * {@code UrlValidator} and {@code AliasPolicy}, called from the service, so each failure has its own
 * {@code errorCode}. Nothing is trimmed (D47). The record holds the URL, so it must never be logged whole.
 *
 * @param originalUrl the URL to shorten
 * @param alias an optional custom alias (D17); missing or JSON null means "generate a code", while an
 *        empty or blank string is invalid (D60)
 */
public record CreateShortUrlRequest(
        @Schema(description = "The URL to shorten: absolute http or https, at most 2048 characters, no embedded "
                + "credentials, and not on this service's own host. Non-ASCII (IDN) hosts are rejected; "
                + "submit the punycode (xn--) form.",
                maxLength = 2048, example = "https://example.com/page")
        @NotBlank(message = "must not be blank") String originalUrl,
        @Schema(description = "Optional custom alias, used as the short code: 3 to 32 characters from A-Z, a-z "
                + "and 0-9, case-sensitive, not a reserved word. Omit or send null to have a code generated.",
                pattern = "^[A-Za-z0-9]{3,32}$", nullable = true, example = "promo2026")
        String alias) {
}
