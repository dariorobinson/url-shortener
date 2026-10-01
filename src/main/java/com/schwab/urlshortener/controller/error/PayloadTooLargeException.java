package com.schwab.urlshortener.controller.error;

import java.io.IOException;

/**
 * D130: thrown while reading a request body without a declared length once it passes the configured limit. An
 * {@link IOException} so it travels through the message converters; the advice maps it to 413 PAYLOAD_TOO_LARGE.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException() {
        super("request body exceeds the configured limit");
    }
}
