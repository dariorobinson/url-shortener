package com.schwab.urlshortener.util.shortcode;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** Generates Base62 codes of the configured length from an injected random source. */
public class SecureRandomShortCodeGenerator implements ShortCodeGenerator {

    /** Base62, case-sensitive character set (D6). */
    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** Smallest code length accepted by {@code ck_short_url_code_format} (D6). */
    public static final int MIN_LENGTH = 3;

    /** Largest code length accepted by {@code ck_short_url_code_format} (D6). */
    public static final int MAX_LENGTH = 32;

    private final RandomGenerator random;
    private final int length;

    /**
     * @throws NullPointerException if {@code random} is null
     * @throws IllegalArgumentException if {@code length} is outside {@code MIN_LENGTH}..{@code MAX_LENGTH}
     */
    public SecureRandomShortCodeGenerator(RandomGenerator random, int length) {
        this.random = Objects.requireNonNull(random, "random");
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "length must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " but was " + length);
        }
        this.length = length;
    }

    @Override
    public String generate() {
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            // nextInt(bound) is uniform (no modulo bias)
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
