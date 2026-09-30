package com.schwab.urlshortener.shortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** D6 format rule, shared by the alias policy and the lookups (D72). Format only: no reserved words. */
class ShortCodeFormatTest {

    @Test
    void shouldAcceptTheLengthBoundariesThreeAndThirtyTwo() {
        assertThat(ShortCodeFormat.isWellFormed("abc")).isTrue();
        assertThat(ShortCodeFormat.isWellFormed("a".repeat(32))).isTrue();
    }

    @Test
    void shouldRejectTheLengthsJustOutsideTheBoundaries() {
        assertThat(ShortCodeFormat.isWellFormed("ab")).isFalse();
        assertThat(ShortCodeFormat.isWellFormed("a".repeat(33))).isFalse();
    }

    @Test
    void shouldAcceptEveryBase62Character() {
        assertThat(ShortCodeFormat.isWellFormed("abcdefghijklmnopqrstuvwxyzABCDEF")).isTrue();
        assertThat(ShortCodeFormat.isWellFormed("GHIJKLMNOPQRSTUVWXYZ0123456789")).isTrue();
    }

    @Test
    void shouldAcceptAReservedWordBecauseReservedWordsAreNotPartOfTheFormat() {
        assertThat(ShortCodeFormat.isWellFormed("health")).isTrue();
        assertThat(ShortCodeFormat.isWellFormed("Admin")).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "a-b", "a_b", "a.b", "a b", "abc ", " abc", "abc\n", "abc/", "abc%20", "abc+",
            "abc@", "abc[", "abc`", "abc{", "abc:", "abc/x", "abc\0"})
    void shouldRejectNullBlankAndEveryNonBase62AsciiCharacter(String code) {
        assertThat(ShortCodeFormat.isWellFormed(code)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcé", "ＡＢＣ", "０１２", "abc٣", "abcß", "日本語", "abc😀"})
    void shouldRejectNonAsciiLettersAndDigits(String code) {
        assertThat(ShortCodeFormat.isWellFormed(code)).isFalse();
    }
}
