package com.schwab.urlshortener.service.exception;

/**
 * The alias is already a short code, in any status (D1). The alias has passed {@code AliasPolicy}, so it
 * is Base62 and safe to put in the message.
 */
public class AliasAlreadyExistsException extends RuntimeException {

    public AliasAlreadyExistsException(String alias, Throwable cause) {
        super("alias already exists: " + alias, cause);
    }
}
