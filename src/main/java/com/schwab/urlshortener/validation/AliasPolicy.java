package com.schwab.urlshortener.validation;

import static com.schwab.urlshortener.shortcode.SecureRandomShortCodeGenerator.MAX_LENGTH;
import static com.schwab.urlshortener.shortcode.SecureRandomShortCodeGenerator.MIN_LENGTH;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Enforces D6 (Base62 alias, {@code MIN_LENGTH} to {@code MAX_LENGTH} characters) and D29
 * (reserved words, case-insensitive; built-in set plus configured additions, D48). Pure: the alias is checked as submitted and is
 * never trimmed. The length bounds are the generator's constants, so they are written once.
 */
public class AliasPolicy {

    /** The D29 words, always reserved (D48). Configuration can add to this set but never remove from it. */
    public static final Set<String> BUILT_IN_RESERVED_WORDS = Set.of(
            "api", "actuator", "v3", "error", "health", "admin", "login", "logout", "static", "assets", "docs");

    private final Set<String> reservedWords;

    /**
     * Reserved words are the built-in D29 set plus {@code additionalReservedWords} (D48). Additions are
     * stripped and lower-cased (Locale.ROOT) once here; blank entries are ignored.
     */
    public AliasPolicy(Collection<String> additionalReservedWords) {
        this.reservedWords = Stream.concat(
                        BUILT_IN_RESERVED_WORDS.stream(),
                        additionalReservedWords.stream()
                                .filter(w -> w != null && !w.isBlank())
                                .map(w -> w.strip().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isValid(String alias) {
        if (alias == null || alias.length() < MIN_LENGTH || alias.length() > MAX_LENGTH) {
            return false;
        }
        // Explicit ASCII loop instead of a regex: no regex engine or Unicode class semantics involved,
        // so only [A-Za-z0-9] can pass by construction.
        for (int i = 0; i < alias.length(); i++) {
            if (!isBase62(alias.charAt(i))) {
                return false;
            }
        }
        return !reservedWords.contains(alias.toLowerCase(Locale.ROOT));
    }

    private static boolean isBase62(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
