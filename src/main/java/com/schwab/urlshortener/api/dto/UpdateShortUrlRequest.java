package com.schwab.urlshortener.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/v1/urls/{code}} (D34). Named for the resource, not the field, so that {@code expiresAt}
 * can be added later (US-013) without a second endpoint or type.
 *
 * @param active false deactivates the link, true reactivates it. Required: missing or JSON null gives
 *        400 VALIDATION_FAILED. A wrapper type, so that "missing" can be seen at all. Only a real JSON
 *        {@code true} or {@code false} is accepted (D89).
 */
public record UpdateShortUrlRequest(
        @Schema(description = "false deactivates the short URL, true reactivates it. Only a JSON boolean is "
                + "accepted.", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
        @NotNull(message = "must not be null") Boolean active) {
}
