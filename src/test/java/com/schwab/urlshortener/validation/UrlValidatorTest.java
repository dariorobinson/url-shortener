package com.schwab.urlshortener.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private static final String BASE = "https://short.example";

    private final UrlValidator validator = new UrlValidator(BASE);

    private static String urlOfLength(int codePoints, String filler) {
        String prefix = "https://other.example/";
        return prefix + filler.repeat(codePoints - prefix.length());
    }

    // AC1
    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example/x",
            "http://other.example",
            "HTTP://OTHER.EXAMPLE/x",
            "hTtPs://other.example/a?b=c#d",
            "https://other.example:8443/x",
            "http://127.0.0.1/x",
            "http://localhost/x",
            "http://[::1]/x",
            "https://other.example/%C3%A9"})
    void shouldAcceptAbsoluteHttpAndHttpsUrlsWithHost(String url) {
        assertThat(validator.isValid(url)).isTrue();
    }

    // AC2
    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://other.example/x",
            "javascript:alert(1)",
            "mailto:a@other.example",
            "//other.example/x",
            "/relative/path",
            "other.example/x",
            "file:///etc/passwd",
            "data:text/plain,hi",
            "http:///path",
            "https://",
            "http:other.example",
            "",
            " "})
    void shouldRejectNonHttpSchemesRelativeUrlsAndMissingHosts(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    // security regression: known bypass inputs, verified against java.net.URI
    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.com\\@short.example",
            "https://short.example%2F@evil",
            "https://evil.com@short.example",
            "https:/x",
            "HTTP://",
            "https://:8080/",
            "https://sh%6Frt.example/",
            "#frag"})
    void shouldRejectKnownBypassInputs(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @NullSource
    void shouldRejectNullWithoutThrowing(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example/a b",
            " https://other.example/x",
            "https://other.example/x ",
            "https://other.example/x\n",
            "\thttps://other.example/x",
            "https://other.exa mple/x",
            "https://other.example/<x>",
            // empty label: getHost() is null (not the trailing-dot rule), so strict parsing rejects it
            "https://short.example../x"})
    void shouldRejectUrlsThatFailStrictParsingAndNeverTrim(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    // AC3, D11, D47
    @Test
    void shouldAcceptUrlOfExactlyMaxLength() {
        String url = urlOfLength(UrlValidator.MAX_LENGTH, "a");
        assertThat(url.length()).isEqualTo(2048);
        assertThat(validator.isValid(url)).isTrue();
    }

    @Test
    void shouldRejectUrlOneCharacterOverMaxLength() {
        assertThat(validator.isValid(urlOfLength(UrlValidator.MAX_LENGTH + 1, "a"))).isFalse();
    }

    @Test
    void shouldRejectUrlAtMaxLengthWithTrailingSpace() {
        String url = urlOfLength(UrlValidator.MAX_LENGTH, "a") + " ";
        assertThat(validator.isValid(url)).isFalse();
        // and a limit-length URL that ends with a space instead of padding is not trimmed to fit
        assertThat(validator.isValid(urlOfLength(UrlValidator.MAX_LENGTH - 1, "a") + " ")).isFalse();
    }

    @Test
    void shouldCountCharactersNotBytesForMultibyteUrlAtLimit() {
        // U+00E9 is 1 char / 2 UTF-8 bytes; the URL is 2048 characters but far over 2048 bytes.
        String twoByte = urlOfLength(UrlValidator.MAX_LENGTH, "é");
        assertThat(twoByte.codePointCount(0, twoByte.length())).isEqualTo(2048);
        assertThat(twoByte.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(2048);
        assertThat(validator.isValid(twoByte)).isTrue();
        assertThat(validator.isValid(twoByte + "é")).isFalse();
    }

    @Test
    void shouldCountSupplementaryCodePointsAsOneCharacter() {
        // U+1F600 is 2 UTF-16 chars but 1 code point, as PostgreSQL char_length counts it.
        String emoji = new String(Character.toChars(0x1F600));
        String atLimit = urlOfLength(UrlValidator.MAX_LENGTH, emoji);
        assertThat(atLimit.codePointCount(0, atLimit.length())).isEqualTo(2048);
        assertThat(atLimit.length()).isGreaterThan(2048);
        assertThat(validator.isValid(atLimit)).isTrue();
        assertThat(validator.isValid(atLimit + emoji)).isFalse();
    }

    // AC4, D11
    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:pass@other.example/path",
            "https://user@other.example/path",
            "https://:pass@other.example/path",
            "https://@other.example/path",
            "http://user:@other.example/"})
    void shouldRejectAnyEmbeddedUserinfo(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    // AC5, D28
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://short.example/x|false",
            "HTTPS://SHORT.EXAMPLE./x|false",
            "https://Short.Example/x|false",
            "https://short.example./|false",
            "https://short.example.evil.com/x|true",
            "https://evil-short.example/x|true",
            "https://sub.short.example/x|true",
            "https://other.example/x|true",
            "https://short.example:8443/x|false",
            "http://short.example/x|false",
            // security regression: authority tricks that must never reach or evade the own-host check
            "https://short.example:/x|false",
            "https://short.example?@evil.com|false",
            "https://short.example#@evil.com|false"})
    void shouldRejectOwnHostAndAcceptOtherHosts(String url, boolean expected) {
        assertThat(validator.isValid(url)).isEqualTo(expected);
    }

    @Test
    void shouldRejectOwnHostOnDifferentPortAndScheme() {
        // D28 compares hosts only: port and scheme are ignored.
        var v = new UrlValidator("https://short.example");
        assertThat(v.isValid("https://short.example:9999/x")).isFalse();
        assertThat(v.isValid("http://short.example/x")).isFalse();
        var http = new UrlValidator("http://short.example:8080");
        assertThat(http.isValid("https://short.example/x")).isFalse();
    }

    @Test
    void shouldCompareOwnHostCaseInsensitivelyAndIgnoreOneTrailingDotOnBaseUrl() {
        var v = new UrlValidator("HTTPS://Short.Example./base");
        assertThat(v.isValid("https://short.example/x")).isFalse();
        assertThat(v.isValid("https://short.example.evil.com/x")).isTrue();
    }

    @Test
    void shouldCompareOwnHostWithRootLocaleRules() {
        // Turkish dotless/dotted I must not make ASCII 'I' match a different host.
        var v = new UrlValidator("https://INFO.example");
        assertThat(v.isValid("https://info.example/x")).isFalse();
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(new UrlValidator("https://INFO.example").isValid("https://info.example/x")).isFalse();
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void shouldTreatNonAsciiHostAsInvalid() {
        // java.net.URI yields no host for a non-ASCII (IDN) authority; punycode must be submitted.
        assertThat(validator.isValid("https://münchen.example/x")).isFalse();
        assertThat(validator.isValid("https://xn--mnchen-3ya.example/x")).isTrue();
    }

    // java.net.URI accepts lone surrogates, but they are rejected because they cannot be encoded as UTF-8
    @ParameterizedTest
    @ValueSource(strings = {
        "https://other.example/x\uD800",
        "https://other.example/\uD800x",
        "https://other.example/x\uDC00",
        "https://other.example/\uDC00x",
        "https://other.example/x\uDC00\uD800",
        "https://other.example/?q=\uD83D"
    })
    void shouldRejectUnpairedSurrogates(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @Test
    void shouldAcceptValidSupplementaryCharacterInPath() {
        assertThat(validator.isValid("https://other.example/x" + new String(Character.toChars(0x1F600)))).isTrue();
        assertThat(validator.isValid("https://other.example/x\uD83D\uDE00")).isTrue();
    }

    // D49: IP-literal, localhost and private hosts are accepted; IDN (non-ASCII) hosts are rejected.
    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/x", "http://10.0.0.1/x", "http://192.168.1.1:8080/x",
            "http://[::1]/x", "http://localhost/x", "http://localhost.test/x"})
    void shouldAcceptIpLiteralLocalhostAndPrivateHostsPerD49(String url) {
        assertThat(validator.isValid(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://bücher.example/x", "https://münchen.example/x"})
    void shouldRejectNonAsciiIdnHostsPerD49(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/relative", "ftp://short.example", "https://", "https://u@short.example", " https://short.example"})
    void shouldRejectInvalidBaseUrlWhenConstructing(String base) {
        assertThatThrownBy(() -> new UrlValidator(base)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullBaseUrlWhenConstructing() {
        assertThatThrownBy(() -> new UrlValidator(null)).isInstanceOf(NullPointerException.class);
    }
}
