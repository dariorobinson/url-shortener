package com.schwab.urlshortener.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
/**
 * Users configured in {@code application-test.yml} (D50) with their plaintext passwords, shared by
 * unit, web-slice and integration tests. Test-only credentials.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestUsers {

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "admin-test-password";
    public static final String ALICE = "alice";
    public static final String ALICE_PASSWORD = "alice-test-password";
    public static final String BOB = "bob";
    public static final String BOB_PASSWORD = "bob-test-password";

    /** Returns the canonical user name, or throws so a typo in a scenario cannot seed or call as nobody. */
    public static String require(String name) {
        return switch (name) {
            case ALICE, BOB, ADMIN -> name;
            default -> throw new IllegalArgumentException("unknown test user: " + name);
        };
    }
}
