package com.schwab.urlshortener.util.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Strict parsing shared by the submitted-URL check and the APP_BASE_URL startup check (D11, D28).
 * Nothing here trims or normalises the input.
 */
public final class HttpUris {

    private HttpUris() {
    }

    /**
     * Parses {@code value} with RFC 3986 rules and returns the URI only if it is absolute, uses
     * {@code http} or {@code https} (case-insensitive), has a non-empty host, and carries no
     * userinfo at all (D11: even a bare username can hold a token). Otherwise returns {@code null}.
     */
    public static URI parseHttpUri(String value) {
        if (value == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getRawUserInfo() != null) {
            return null;
        }
        return uri;
    }

    /** Lower-cases with {@link Locale#ROOT} and removes at most one trailing dot (D28). */
    public static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }
}
