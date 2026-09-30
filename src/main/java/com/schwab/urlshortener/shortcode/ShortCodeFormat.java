package com.schwab.urlshortener.shortcode;

import static com.schwab.urlshortener.shortcode.SecureRandomShortCodeGenerator.MAX_LENGTH;
import static com.schwab.urlshortener.shortcode.SecureRandomShortCodeGenerator.MIN_LENGTH;

/**
 * The D6 code format: {@code [A-Za-z0-9]}, {@code MIN_LENGTH} to {@code MAX_LENGTH} characters. Mirrors
 * {@code ck_short_url_code_format}. Format only: reserved words (D29, D48) are an alias-creation rule and live in
 * {@code AliasPolicy}, so an existing code that later becomes reserved stays reachable. Shared by the alias policy,
 * the management API lookup (D72) and the redirect (D72).
 */
public final class ShortCodeFormat {

    private ShortCodeFormat() {
    }

    /** @return true only for a non-null code of 3 to 32 ASCII letters and digits; never trims or folds case */
    public static boolean isWellFormed(String code) {
        if (code == null || code.length() < MIN_LENGTH || code.length() > MAX_LENGTH) {
            return false;
        }
        // Explicit ASCII loop instead of a regex: no regex engine or Unicode class semantics involved,
        // so only [A-Za-z0-9] can pass by construction.
        for (int i = 0; i < code.length(); i++) {
            if (!isBase62(code.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBase62(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
