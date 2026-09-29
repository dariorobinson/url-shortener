package com.schwab.urlshortener.support;

/**
 * Users configured in {@code application-test.yml} (D50) with their plaintext passwords, shared by
 * unit, web-slice and integration tests. Test-only credentials.
 */
public final class TestUsers {

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "admin-test-password";
    public static final String ALICE = "alice";
    public static final String ALICE_PASSWORD = "alice-test-password";
    public static final String BOB = "bob";
    public static final String BOB_PASSWORD = "bob-test-password";

    private TestUsers() {
    }
}
