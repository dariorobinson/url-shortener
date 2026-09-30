package com.schwab.urlshortener.service.exception;

/** The submitted alias failed {@code AliasPolicy}. The message never contains the alias (arbitrary text). */
public class InvalidAliasException extends RuntimeException {

    public InvalidAliasException() {
        super("alias is not acceptable");
    }
}
