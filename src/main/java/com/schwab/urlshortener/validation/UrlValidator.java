package com.schwab.urlshortener.validation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Enforces the submitted-URL rules of D11: absolute http/https with a host, at most
 * {@value #MAX_LENGTH} characters, no embedded credentials, and no link to this service's own
 * host (D28). Pure: no I/O, and the input is never trimmed.
 */
public class UrlValidator {

    /**
     * Maximum URL length in Unicode code points, matching PostgreSQL's {@code char_length} in
     * {@code ck_short_url_original_url_length} (D11, D47).
     */
    public static final int MAX_LENGTH = 2048;

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

    public boolean isValid(String url) {
        if (url == null || url.codePointCount(0, url.length()) > MAX_LENGTH) {
            return false;
        }
        // Unpaired surrogates cannot be encoded as UTF-8 (and PostgreSQL would reject them), yet
        // java.net.URI accepts them. The encoder is not thread-safe, so a new one is made per call.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(url)) {
            return false;
        }
        URI uri = HttpUris.parseHttpUri(url);
        return uri != null && !ownHost.equals(HttpUris.normalizeHost(uri.getHost()));
    }
}
