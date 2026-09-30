package com.schwab.urlshortener.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The D75 encoder shared by the redirect and {@link UrlValidator} (D84).
 */
class LocationEncoderTest {

    private static Stream<Arguments> encodings() {
        return Stream.of(
                // ASCII is byte-identical: case, escapes, dot segments, query, fragment, empty pieces.
                Arguments.of("https://Example.COM:8443/a/../b;p?q=a+b&c=%2f%2F#frag",
                        "https://Example.COM:8443/a/../b;p?q=a+b&c=%2f%2F#frag"),
                Arguments.of("https://example.com", "https://example.com"),
                Arguments.of("https://example.com/?", "https://example.com/?"),
                Arguments.of("https://example.com/#", "https://example.com/#"),
                Arguments.of("https://example.com/a%20b", "https://example.com/a%20b"),
                Arguments.of("https://example.com/%c3%a9%C3%A9", "https://example.com/%c3%a9%C3%A9"),
                Arguments.of("https://example.com/~!$&'()*+,;=:@[]{}|", "https://example.com/~!$&'()*+,;=:@[]{}|"),
                // Non-ASCII becomes upper-case UTF-8 percent escapes.
                Arguments.of("https://example.com/café", "https://example.com/caf%C3%A9"),
                Arguments.of("é", "%C3%A9"),
                Arguments.of("https://example.com/中", "https://example.com/%E4%B8%AD"),
                Arguments.of("https://example.com/😀", "https://example.com/%F0%9F%98%80"),
                Arguments.of("https://example.com/café?q=ü#ß",
                        "https://example.com/caf%C3%A9?q=%C3%BC#%C3%9F"),
                // No normalisation: a decomposed e plus combining acute stays two sequences.
                Arguments.of("é", "e%CC%81"),
                // An existing escape next to a raw character: only the raw character is encoded.
                Arguments.of("%C3%A9é", "%C3%A9%C3%A9"),
                // Controls, space and DEL can never reach a header.
                Arguments.of("a b", "a%20b"),
                Arguments.of("a\r\nb", "a%0D%0Ab"),
                Arguments.of("a\tb", "a%09b"),
                Arguments.of("a\u007Fb", "a%7Fb"),
                Arguments.of("a\u0000b", "a%00b"),
                // A lone surrogate becomes "?" in UTF-8, so it is sent as %3F. This is unreachable in production:
                // D11 rejects such a URL at create time. The row only pins the encoder's current behaviour.
                Arguments.of("a\uD800b", "a%3Fb"),
                Arguments.of("", ""));
    }

    @ParameterizedTest
    @MethodSource("encodings")
    void shouldEncodeOnlyCharactersOutsidePrintableAscii(String stored, String expected) {
        String header = LocationEncoder.encode(stored);

        assertThat(header).isEqualTo(expected);
        assertThat(header).matches("^[\\x21-\\x7E]*$");
    }

    @Test
    void shouldLeaveA2048CharacterAsciiUrlByteIdentical() {
        String url = String.format(Locale.ROOT, "https://example.com/%s?q=1", "aB%2f".repeat(400) + "x".repeat(24));
        assertThat(url).hasSize(2048);

        assertThat(LocationEncoder.encode(url)).isEqualTo(url);
    }

    @Test
    void shouldEncodeEveryNonAsciiCodePointOfAMixedStringAsUpperCaseUtf8Hex() {
        String mixed = "é中😀";

        String header = LocationEncoder.encode(mixed);

        StringBuilder expected = new StringBuilder();
        for (byte b : mixed.getBytes(StandardCharsets.UTF_8)) {
            expected.append(String.format("%%%02X", b & 0xFF));
        }
        assertThat(header).isEqualTo(expected.toString());
    }
}
