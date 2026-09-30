package com.schwab.urlshortener.service.exception;

/** The submitted originalUrl failed {@code UrlValidator}. The message never contains the URL. */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException() {
        super("originalUrl is not acceptable");
    }
}
