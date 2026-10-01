package com.schwab.urlshortener.service.exception;

/**
 * A PATCH body with neither field, or with {@code "active": null} (D114). Mapped to exactly the VALIDATION_FAILED
 * body the {@code @NotNull} constraint on {@code active} produced before expiration existed, so existing clients see
 * no change. Carries no data.
 */
public class InvalidUpdateRequestException extends RuntimeException {

    public InvalidUpdateRequestException() {
        super("update body rejected");
    }
}
