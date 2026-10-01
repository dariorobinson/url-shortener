package com.schwab.urlshortener.util.domain.exception;

import lombok.Getter;

/**
 * Thrown when any lifecycle transition is attempted on a {@code ShortUrl} that is already
 * {@code DELETED}, including a second {@code softDelete} (D46). Mapped to
 * {@code 404 SHORT_URL_NOT_FOUND} in US-009 (D13/D36).
 */
@Getter
public class ShortUrlDeletedException extends RuntimeException {

    private final String shortCode;

    public ShortUrlDeletedException(String shortCode) {
        super("Short URL %s is deleted".formatted(shortCode));
        this.shortCode = shortCode;
    }
}
