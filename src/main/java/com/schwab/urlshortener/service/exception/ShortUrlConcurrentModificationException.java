package com.schwab.urlshortener.service.exception;

/**
 * Another request changed the short URL between this request's read and its versioned write (D35, D87). Mapped to
 * {@code 409 CONCURRENT_MODIFICATION}. It carries no code and a fixed message, so nothing about the row can reach a
 * client or a log through it. The name avoids {@link java.util.ConcurrentModificationException}.
 */
public class ShortUrlConcurrentModificationException extends RuntimeException {

    /** @param cause the optimistic-locking failure that was translated */
    public ShortUrlConcurrentModificationException(Throwable cause) {
        super("Short URL was modified concurrently", cause);
    }
}
