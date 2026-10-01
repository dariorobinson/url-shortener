package com.schwab.urlshortener.exception;

import lombok.Getter;

/**
 * Thrown when {@code deactivate()} is called on a {@code ShortUrl} that is already
 * {@code DEACTIVATED} (D26). Mapped to {@code 409 SHORT_URL_ALREADY_DEACTIVATED} in US-009.
 */
@Getter
public class ShortUrlAlreadyDeactivatedException extends RuntimeException {

    private final String shortCode;

    public ShortUrlAlreadyDeactivatedException(String shortCode) {
        super("Short URL %s is already deactivated".formatted(shortCode));
        this.shortCode = shortCode;
    }
}
