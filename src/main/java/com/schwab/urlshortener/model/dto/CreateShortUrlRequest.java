package com.schwab.urlshortener.model.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.schwab.urlshortener.util.validation.StrictOffsetDateTimeDeserializer;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;

/**
 * Body of {@code POST /api/v1/urls}. Bean Validation is structural only: every other rule belongs to
 * {@code UrlValidator} and {@code AliasPolicy}, called from the service, so each failure has its own
 * {@code errorCode}. Nothing is trimmed (D47). The record holds the URL, so it must never be logged whole.
 *
 * @param originalUrl the URL to shorten: absolute http or https, at most 2048 characters, no embedded
 *        credentials, and not on this service's own host. Non-ASCII (IDN) hosts are rejected; the client
 *        submits the punycode (xn--) form
 * @param alias an optional custom alias (D17), used as the short code: 3 to 32 characters from A-Z, a-z and 0-9,
 *        case-sensitive, not a reserved word; missing or JSON null means "generate a code", while an
 *        empty or blank string is invalid (D60)
 * @param expiresAt an optional expiry: an ISO-8601 date-time with an explicit offset or Z, strictly in the future
 *        and at most 10 years ahead; missing or JSON null means the link never expires. Numbers and date-times
 *        without an offset are rejected
 */
public record CreateShortUrlRequest(
        @NotBlank(message = "must not be blank") String originalUrl,
        String alias,
        @JsonDeserialize(using = StrictOffsetDateTimeDeserializer.class) OffsetDateTime expiresAt) {
}
