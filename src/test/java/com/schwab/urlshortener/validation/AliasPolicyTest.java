package com.schwab.urlshortener.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AliasPolicyTest {

    // D29 built-in words, spelled out here so the test does not depend on the production constant
    private static final List<String> BUILT_INS = List.of(
            "api", "actuator", "v3", "error", "health", "admin", "login", "logout", "static", "assets", "docs");

    // No additional words configured: only the built-ins are reserved (D48)
    private final AliasPolicy policy = new AliasPolicy(List.of());

    // AC6
    @ParameterizedTest
    @ValueSource(strings = {"abc", "ABC", "a1B2c3", "0000", "myapi", "apix", "xapi", "api1", "Zz9"})
    void shouldAcceptBase62AliasesOfValidLength(String alias) {
        assertThat(policy.isValid(alias)).isTrue();
    }

    @Test
    void shouldAcceptAliasOfExactlyThreeAndThirtyTwoCharacters() {
        assertThat(policy.isValid("a".repeat(3))).isTrue();
        assertThat(policy.isValid("a".repeat(32))).isTrue();
    }

    // AC7
    @Test
    void shouldRejectAliasShorterThanThreeOrLongerThanThirtyTwo() {
        assertThat(policy.isValid("")).isFalse();
        assertThat(policy.isValid("a")).isFalse();
        assertThat(policy.isValid("ab")).isFalse();
        assertThat(policy.isValid("a".repeat(33))).isFalse();
    }

    @Test
    void shouldRejectAliasWithTrailingOrLeadingWhitespaceWithoutTrimming() {
        assertThat(policy.isValid("a".repeat(32) + " ")).isFalse();
        assertThat(policy.isValid("a".repeat(31) + " ")).isFalse();
        assertThat(policy.isValid("abc ")).isFalse();
        assertThat(policy.isValid(" abc")).isFalse();
        assertThat(policy.isValid("abc\n")).isFalse();
        assertThat(policy.isValid("abc\t")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab_c", "ab-c", "_abc", "abc-", "a b c", "abc!", "ab.c", "ab/c",
            "abcé", "straße", "ééé", "ＡＢＣ", "ab\u0000c"})
    void shouldRejectCharactersOutsideBase62IncludingUnderscoreHyphenAndNonAscii(String alias) {
        assertThat(policy.isValid(alias)).isFalse();
    }

    @Test
    void shouldCountCharactersNotBytesForMultibyteAlias() {
        // 3 characters (6 bytes) is within the length bound but still not Base62
        assertThat(policy.isValid("ééé")).isFalse();
        // 32 x 'a' accepted vs 16 two-byte chars (32 bytes) rejected on characters
        assertThat(policy.isValid("é".repeat(16))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    void shouldRejectNullWithoutThrowing(String alias) {
        assertThat(policy.isValid(alias)).isFalse();
    }

    // AC8, D29
    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "Api", "aPi", "actuator", "v3", "V3", "error", "health", "Admin",
            "admin", "login", "logout", "static", "assets", "docs", "DOCS"})
    void shouldRejectBuiltInReservedWordsCaseInsensitivelyWithNoConfiguration(String alias) {
        assertThat(policy.isValid(alias)).isFalse();
    }

    // D48
    @Test
    void shouldHoldExactlyTheD29WordsAsBuiltIns() {
        assertThat(AliasPolicy.BUILT_IN_RESERVED_WORDS).containsExactlyInAnyOrderElementsOf(BUILT_INS);
    }

    @Test
    void shouldRejectBuiltInsStillWhenAdditionalWordsAreConfigured() {
        var custom = new AliasPolicy(List.of("Promo"));
        for (String word : BUILT_INS) {
            assertThat(custom.isValid(word)).as(word).isFalse();
        }
    }

    @Test
    void shouldRejectAdditionalWordCaseInsensitively() {
        var custom = new AliasPolicy(List.of("Promo"));
        assertThat(custom.isValid("promo")).isFalse();
        assertThat(custom.isValid("PROMO")).isFalse();
        assertThat(custom.isValid("pRoMo")).isFalse();
        assertThat(custom.isValid("promos")).isTrue();
    }

    @Test
    void shouldTreatConfiguringABuiltInAsAdditionalWordAsHarmless() {
        var custom = new AliasPolicy(List.of("API", "docs", "promo"));
        for (String word : BUILT_INS) {
            assertThat(custom.isValid(word)).as(word).isFalse();
        }
        assertThat(custom.isValid("promo")).isFalse();
        assertThat(custom.isValid("myapi")).isTrue();
    }

    @Test
    void shouldStripAdditionalWords() {
        assertThat(new AliasPolicy(List.of(" promo ")).isValid("promo")).isFalse();
    }

    @Test
    void shouldLowerCaseReservedWordsWithRootLocale() {
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            var turkish = new AliasPolicy(List.of("INFO"));
            assertThat(turkish.isValid("info")).isFalse();
            assertThat(turkish.isValid("INFO")).isFalse();
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void shouldIgnoreBlankAndNullEntriesInConfiguredList() {
        var p = new AliasPolicy(Arrays.asList("", "  ", null, "promo"));
        assertThat(p.isValid("promo")).isFalse();
        assertThat(p.isValid("abc")).isTrue();
    }

    @Test
    void shouldKeepBuiltInsActiveWhenAdditionalListIsEmpty() {
        var empty = new AliasPolicy(List.of());
        for (String word : BUILT_INS) {
            assertThat(empty.isValid(word)).as(word).isFalse();
        }
        assertThat(empty.isValid("abc")).isTrue();
    }
}
