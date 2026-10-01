package com.schwab.urlshortener.exception;

import lombok.Getter;

/**
 * Thrown when {@code reactivate()} is called on a {@code ShortUrl} that is already
 * {@code ACTIVE} (D26). Mapped to {@code 409 SHORT_URL_ALREADY_ACTIVE} in US-009.
 */
@Getter
public class ShortUrlAlreadyActiveException extends RuntimeException {

    private final String shortCode;

    public ShortUrlAlreadyActiveException(String shortCode) {
        super("Short URL %s is already active".formatted(shortCode));
        this.shortCode = shortCode;
    }
}
