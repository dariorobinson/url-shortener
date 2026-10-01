package com.schwab.urlshortener.util.validation;

import com.schwab.urlshortener.util.shortcode.ShortCodeFormat;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Enforces D6 (format, delegated to {@link ShortCodeFormat}) and D29
 * (reserved words, case-insensitive; built-in set plus configured additions, D48). Pure: the
 * alias is checked as submitted and is never trimmed. The format rule is written once, in
 * {@link ShortCodeFormat}.
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
        return ShortCodeFormat.isWellFormed(alias) && !reservedWords.contains(alias.toLowerCase(Locale.ROOT));
    }
}
