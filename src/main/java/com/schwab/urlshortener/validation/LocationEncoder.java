package com.schwab.urlshortener.validation;

import java.nio.charset.StandardCharsets;

/**
 * The D75 encoder, shared by the redirect (which sends its result as {@code Location}) and by
 * {@link UrlValidator} (which measures it for the D84 byte limit), so the rule is defined once. It lives in
 * {@code validation} because {@code api} already depends on this package and {@code validation} must not depend
 * on {@code api}.
 */
public final class LocationEncoder {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private LocationEncoder() {
    }

    /**
     * D75: every code point outside printable ASCII (0x21 to 0x7E) becomes the upper-case {@code %XX} form of its
     * UTF-8 bytes. Everything else, existing escapes included, is copied unchanged: no parsing, no normalisation.
     * The result is pure ASCII, so its {@code length()} is its byte length.
     *
     * @throws NullPointerException if target is null
     */
    public static String encode(String target) {
        StringBuilder out = new StringBuilder(target.length());
        target.codePoints().forEach(cp -> {
            if (cp > 0x20 && cp < 0x7F) {
                out.append((char) cp);
            } else {
                for (byte b : Character.toString(cp).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            }
        });
        return out.toString();
    }
}
