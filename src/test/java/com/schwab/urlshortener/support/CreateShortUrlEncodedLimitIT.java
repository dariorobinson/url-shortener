package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.util.validation.LocationEncoder;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D84 through real HTTP: create accepts a URL whose D75-encoded form is exactly 2048 bytes and rejects 2049, for
 * multi-byte content (CJK: 9 encoded bytes per character, emoji: 12). The rejected URL is counted by its own
 * value, which is unique per test case, so "no row" cannot pass because of an unrelated truncate.
 */
class CreateShortUrlEncodedLimitIT extends IntegrationTestBase {

    private static final Set<String> PROBLEM_WITH_ERRORS_KEYS =
            new TreeSet<>(Set.of("type", "title", "status", "detail", "instance", "errorCode", "errors"));

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CJK", "emoji"})
    void shouldReturn201WhenEncodedUrlIsExactly2048Bytes(String kind) throws Exception {
        String url = EncodedUrls.withEncodedLength(EncodedUrls.unitFor(kind), 2048);
        assertThat(LocationEncoder.encode(url)).hasSize(2048);
        assertThat(url.length()).as("D11 characters").isLessThanOrEqualTo(2048);
        assertThat(url).as("multi-byte content is really present").contains(EncodedUrls.unitFor(kind));

        HttpResponse<String> response = api.post(TestUsers.ALICE, api.createBody(url, null));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(api.json(response).path("originalUrl").asText()).isEqualTo(url);
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CJK", "emoji"})
    void shouldReturn400InvalidUrlAndStoreNothingWhenEncodedUrlIs2049Bytes(String kind) throws Exception {
        String url = EncodedUrls.withEncodedLength(EncodedUrls.unitFor(kind), 2049);
        assertThat(LocationEncoder.encode(url)).hasSize(2049);
        assertThat(url.length()).as("within D11, so only D84 can reject it").isLessThanOrEqualTo(2048);

        HttpResponse<String> response = api.post(TestUsers.ALICE, api.createBody(url, null));

        assertInvalidUrl(response);
        assertThat(data.countByOriginalUrl(url)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM short_url", Integer.class)).isZero();
    }

    @Test
    void shouldReturn400When900CjkCharactersFillTheOldEightKilobyteRedirectBoundary() throws Exception {
        // 900 CJK characters plus the prefix is 920 characters (valid under D11) but about 8 KB encoded.
        String url = EncodedUrls.PREFIX + EncodedUrls.CJK.repeat(900);
        assertThat(url.length()).isLessThanOrEqualTo(2048);
        assertThat(LocationEncoder.encode(url).length()).isGreaterThan(8000);

        HttpResponse<String> response = api.post(TestUsers.ALICE, api.createBody(url, null));

        assertInvalidUrl(response);
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    private void assertInvalidUrl(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = api.json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_WITH_ERRORS_KEYS);
        assertThat(body.path("errorCode").asText()).isEqualTo("INVALID_URL");
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("originalUrl");
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }
}
