package com.schwab.urlshortener.util.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.config.AppProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D33: links come only from APP_BASE_URL, joined with exactly one slash. */
class ShortUrlLinksTest {

    @ParameterizedTest
    @CsvSource({
            "https://short.example,          https://short.example/abc1234",
            "https://short.example/,         https://short.example/abc1234",
            "https://short.example///,       https://short.example/abc1234",
            "https://short.example/base,     https://short.example/base/abc1234",
            "https://short.example/base/,    https://short.example/base/abc1234",
            "http://short.example:8080,      http://short.example:8080/abc1234"})
    void shouldJoinTheConfiguredBaseAndCodeWithASingleSlash(String base, String expected) {
        ShortUrlLinks links = new ShortUrlLinks(new AppProperties(base));

        assertThat(links.publicUrl("abc1234")).isEqualTo(expected);
    }

    @Test
    void shouldKeepTheConfiguredBaseCaseUnchanged() {
        ShortUrlLinks links = new ShortUrlLinks(new AppProperties("https://Short.Example/Base"));

        assertThat(links.publicUrl("abc1234")).isEqualTo("https://Short.Example/Base/abc1234");
    }

    @Test
    void shouldBuildARelativeManagementLocation() {
        ShortUrlLinks links = new ShortUrlLinks(new AppProperties("https://short.example"));

        URI location = links.location("abc1234");

        assertThat(location).isEqualTo(URI.create("/api/v1/urls/abc1234"));
        assertThat(location.isAbsolute()).isFalse();
    }
}
