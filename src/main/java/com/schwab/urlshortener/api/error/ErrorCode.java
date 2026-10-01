package com.schwab.urlshortener.api.error;

import org.springframework.http.HttpStatus;

/**
 * The complete catalogue of {@code errorCode} values returned by the API (D31, extended by D61, D109 and D130). Each
 * constant carries
 * the HTTP status fixed for it elsewhere, so a producer never keeps a second mapping. Later stories
 * reuse these values verbatim.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_URL(HttpStatus.BAD_REQUEST),
    INVALID_ALIAS(HttpStatus.BAD_REQUEST),
    ALIAS_ALREADY_EXISTS(HttpStatus.CONFLICT),
    SHORT_URL_NOT_FOUND(HttpStatus.NOT_FOUND),
    SHORT_URL_ALREADY_DEACTIVATED(HttpStatus.CONFLICT),
    SHORT_URL_ALREADY_ACTIVE(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    SHORT_CODE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    // D61: framework errors that no D31 code could carry
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    // D109: the public redirect of an ACTIVE link whose expiry has passed
    SHORT_URL_EXPIRED(HttpStatus.GONE),
    // D130: a request body over the configured limit
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
