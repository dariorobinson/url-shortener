package com.schwab.urlshortener.service.exception;

/** Every generation attempt collided or was rejected, so no code could be allocated (FR-10). */
public class ShortCodeUnavailableException extends RuntimeException {

    public ShortCodeUnavailableException(int attempts) {
        super("no short code allocated after " + attempts + " attempts");
    }
}
