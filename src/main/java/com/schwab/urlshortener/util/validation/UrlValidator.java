package com.schwab.urlshortener.util.validation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Enforces the submitted-URL rules of D11: absolute http/https with a host, at most
 * {@value #MAX_LENGTH} characters, no embedded credentials, and no link to this service's own
 * host (D28). D84 adds a limit of {@value #MAX_ENCODED_BYTES} bytes on the {@link LocationEncoder} form.
 * Pure: no I/O, and the input is never trimmed.
 */
public class UrlValidator {

    /**
     * Maximum URL length in Unicode code points, matching PostgreSQL's {@code char_length} in
     * {@code ck_short_url_original_url_length} (D11, D47).
     */
    public static final int MAX_LENGTH = 2048;

    /**
     * Maximum length in bytes of the {@link LocationEncoder} (D75) form of a URL, which is what the redirect sends as
     * {@code Location} (D84). The encoded form is pure ASCII, so its character count is its byte count.
     */
    public static final int MAX_ENCODED_BYTES = 2048;

    private final String ownHost;

    /**
     * @param appBaseUrl the configured APP_BASE_URL, whose host is "own host" (D28)
     * @throws NullPointerException if appBaseUrl is null
     * @throws IllegalArgumentException if appBaseUrl is not an absolute http or https URL with a host
     */
    public UrlValidator(String appBaseUrl) {
        URI base = HttpUris.parseHttpUri(Objects.requireNonNull(appBaseUrl, "appBaseUrl"));
        if (base == null) {
            throw new IllegalArgumentException("appBaseUrl must be an absolute http or https URL with a host");
        }
        this.ownHost = HttpUris.normalizeHost(base.getHost());
    }

    /**
     * @return true if the URL passes every D11 rule and its encoded form is at most {@value #MAX_ENCODED_BYTES}
     *         bytes (D84); false otherwise, including for null. Never throws for bad input.
     */
    public boolean isValid(String url) {
        // D84 implies the D11 count: every code point becomes at least one character of the encoded form, so a
        // form within MAX_ENCODED_BYTES already bounds the code-point count. The D11 check stays as the cheap
        // limit that runs before the encoding allocates anything.
        if (url == null || url.codePointCount(0, url.length()) > MAX_LENGTH) {
            return false;
        }
        // Unpaired surrogates cannot be encoded as UTF-8 (and PostgreSQL would reject them), yet
        // java.net.URI accepts them. The encoder is not thread-safe, so a new one is made per call.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(url)) {
            return false;
        }
        // D84: the redirect's Location (the D75 form) must stay within the byte limit.
        if (LocationEncoder.encode(url).length() > MAX_ENCODED_BYTES) {
            return false;
        }
        URI uri = HttpUris.parseHttpUri(url);
        return uri != null && !ownHost.equals(HttpUris.normalizeHost(uri.getHost()));
    }
}
