package com.schwab.urlshortener.service.exception;

/** The redirect of an expired link (D109). Carries no data: the code is logged by the service, never echoed. */
public class ShortUrlExpiredException extends RuntimeException {

    public ShortUrlExpiredException() {
        super("short URL expired");
    }
}
