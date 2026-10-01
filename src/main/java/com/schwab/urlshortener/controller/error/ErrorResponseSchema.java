package com.schwab.urlshortener.controller.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Documentation only, never instantiated: the OpenAPI schema of every error body. springdoc models
 * {@code ProblemDetail} with a nested {@code properties} map, which would misdocument the top-level
 * {@code errorCode}.
 */
@Schema(name = "Problem", description = "RFC 7807 problem document with an errorCode extension")
public record ErrorResponseSchema(
        @Schema(example = "about:blank") String type,
        @Schema(example = "Bad Request") String title,
        @Schema(example = "400") int status,
        @Schema(description = "A generic, fixed message; never internal details") String detail,
        @Schema(description = "The request path, without a query string", example = "/api/v1/urls") String instance,
        @Schema(description = "Machine-readable error code", example = "INVALID_ALIAS") String errorCode,
        @Schema(description = "Present only when errorCode is VALIDATION_FAILED, INVALID_URL or INVALID_ALIAS. "
                + "Sorted by field; never contains the rejected value.", nullable = true)
        List<FieldViolation> errors) {
}
