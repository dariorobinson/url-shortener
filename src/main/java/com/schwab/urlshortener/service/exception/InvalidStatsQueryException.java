package com.schwab.urlshortener.service.exception;

import java.util.List;
import java.util.Objects;

/**
 * One or more stats query parameters failed validation (D96, D97, D98, D99). It carries only a fixed parameter
 * name and a fixed rule text per violation, never the submitted value, so neither the message nor the response body
 * can echo it (D56).
 */
public class InvalidStatsQueryException extends RuntimeException {

    private final transient List<Violation> violations;

    /**
     * @param violations at least one; built only from constants, never from client text
     * @throws IllegalArgumentException if violations is empty
     */
    public InvalidStatsQueryException(List<Violation> violations) {
        super("stats query is not acceptable");
        Objects.requireNonNull(violations, "violations");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    /**
     * @param parameter the query parameter, from the fixed {@link StatsParameter} set
     * @param rule      the fixed rule text
     */
    public record Violation(StatsParameter parameter, String rule) {
    }
}
