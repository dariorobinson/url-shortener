package com.schwab.urlshortener.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.support.IntegrationTestBase;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Black-box check that the beans wired from real configuration in the full context enforce
 * D11/D28/D29: own host comes from the test profile's app.base-url (short.example, D62), and the default
 * D29 reserved list is active. Deliberately small; the input tables live in the unit tests.
 */
class ValidationWiringIT extends IntegrationTestBase {

    @Autowired
    private UrlValidator urlValidator;

    @Autowired
    private AliasPolicy aliasPolicy;

    @Test
    void shouldRejectOwnHostFromConfiguredBaseUrlIgnoringCaseTrailingDotPortAndScheme() {
        assertThat(urlValidator.isValid("https://short.example/x")).isFalse();
        assertThat(urlValidator.isValid("http://short.example:8080/x")).isFalse();
        assertThat(urlValidator.isValid("HTTPS://SHORT.EXAMPLE./x")).isFalse();
        assertThat(urlValidator.isValid("http://short.example/x")).isFalse();
    }

    @Test
    void shouldAcceptHostsThatOnlyResembleTheConfiguredOwnHost() {
        assertThat(urlValidator.isValid("https://short.example.evil.com/x")).isTrue();
        assertThat(urlValidator.isValid("https://sub.short.example/x")).isTrue();
        assertThat(urlValidator.isValid("http://localhost:8080/x")).as("localhost is no longer the own host").isTrue();
        assertThat(urlValidator.isValid("https://other.example/x")).isTrue();
    }

    @Test
    void shouldApplyUrlRulesInFullContext() {
        assertThat(urlValidator.isValid("https://other.example/x")).isTrue();
        assertThat(urlValidator.isValid("ftp://other.example/x")).isFalse();
        assertThat(urlValidator.isValid("https://user:pass@other.example/x")).isFalse();
        String base = "https://other.example/";
        assertThat(urlValidator.isValid(base + "a".repeat(2048 - base.length()))).isTrue();
        assertThat(urlValidator.isValid(base + "a".repeat(2048 - base.length()) + " ")).isFalse();
    }

    @Test
    void shouldRejectEveryDefaultReservedWordCaseInsensitively() {
        for (String word : new String[] {"api", "actuator", "v3", "error", "health", "admin",
                "login", "logout", "static", "assets", "docs"}) {
            assertThat(aliasPolicy.isValid(word)).as(word).isFalse();
            assertThat(aliasPolicy.isValid(word.toUpperCase(Locale.ROOT))).as(word.toUpperCase(Locale.ROOT)).isFalse();
        }
        assertThat(aliasPolicy.isValid("myapi")).isTrue();
    }

    @Test
    void shouldNotTrimAliasesInFullContext() {
        assertThat(aliasPolicy.isValid("a".repeat(32))).isTrue();
        assertThat(aliasPolicy.isValid("a".repeat(32) + " ")).isFalse();
        assertThat(aliasPolicy.isValid("abc\n")).isFalse();
        assertThat(aliasPolicy.isValid("ab")).isFalse();
        assertThat(aliasPolicy.isValid("a".repeat(33))).isFalse();
    }
}
