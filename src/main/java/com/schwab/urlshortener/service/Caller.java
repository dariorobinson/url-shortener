package com.schwab.urlshortener.service;

import java.util.Objects;

/**
 * Who is calling, as the service needs it (D4). Built by the API layer from the Authentication, so the service
 * has no Spring Security dependency. It holds a username (personal data) and must never be logged.
 *
 * @param username the configured lowercase username (D51, D54); never blank
 * @param admin    true only if the caller holds {@code ROLE_ADMIN} itself (not through the hierarchy)
 */
public record Caller(String username, boolean admin) {

    /**
     * @throws NullPointerException     if username is null
     * @throws IllegalArgumentException if username is blank
     */
    public Caller {
        Objects.requireNonNull(username, "username");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
    }
}
