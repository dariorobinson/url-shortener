package com.schwab.urlshortener.service.exception;

/**
 * The short URL is not visible to the caller: the code is malformed (D72) or unknown, the link is DELETED (D13),
 * or the caller is neither its creator nor ADMIN (D4). The four causes are deliberately indistinguishable, so the
 * exception carries no code and no reason.
 */
public class ShortUrlNotFoundException extends RuntimeException {

    public ShortUrlNotFoundException() {
        super("Short URL not found");
    }
}
