package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.config.ShortCodeProperties;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Forced short-code collisions over real HTTP against PostgreSQL, through the scripted generator
 * seam: AC6 (retry in a fresh transaction), AC7 (exhaustion, nothing committed) and AC15
 * (reserved-word codes are retried). A real unique-constraint violation aborts the PostgreSQL
 * transaction, so a retry only succeeds if it runs in a fresh one.
 */
class ShortCodeCollisionIT extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedShortCodeGenerator generator;

    @Autowired
    private ShortCodeProperties properties;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        generator.reset();
        data.truncate();
    }

    @AfterEach
    void tearDown() {
        generator.reset();
    }

    private static String marker(String label) {
        return "https://example.com/" + label + "/" + UUID.randomUUID();
    }

    private HttpResponse<String> create(String url) throws Exception {
        return api.post(TestUsers.ALICE, api.createBody(url, null));
    }

    // ---- AC6 ----

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DEACTIVATED", "DELETED"})
    void shouldRetryInFreshTransactionAndReturn201WhenFirstGeneratedCodeCollides(String status) throws Exception {
        data.seed("Coll1de", status);
        generator.willReturn("Coll1de");
        String url = marker("ac6");

        HttpResponse<String> response = create(url);

        assertThat(response.statusCode()).isEqualTo(201);
        String code = api.json(response).path("shortCode").asText();
        assertThat(code).isNotEqualTo("Coll1de").matches("^[A-Za-z0-9]{7}$");
        assertThat(generator.calls()).as("first attempt collided, second succeeded").isEqualTo(2);
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
        assertThat(data.countByCodeAndOwner(code, TestUsers.ALICE)).isEqualTo(1);
        assertThat(data.countByCode("Coll1de")).as("colliding row unchanged").isEqualTo(1);
        assertThat(data.createdBy("Coll1de")).isEqualTo(ShortUrlTestData.SEED_OWNER);
        assertThat(jdbc.queryForObject("SELECT status FROM short_url WHERE short_code = 'Coll1de'", String.class))
                .isEqualTo(status);
    }

    @Test
    void shouldSucceedOnTheLastAllowedAttemptWhenEarlierAttemptsCollide() throws Exception {
        int max = properties.maxAttempts();
        List<String> colliding = new ArrayList<>();
        for (int i = 1; i < max; i++) {
            String code = "Last%03d".formatted(i);
            data.seed(code, "ACTIVE");
            colliding.add(code);
        }
        generator.willReturn(colliding.toArray(String[]::new));
        String url = marker("ac6-last");

        HttpResponse<String> response = create(url);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(generator.calls()).isEqualTo(max);
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
    }

    // ---- AC7 ----

    @Test
    void shouldReturn503AndCommitNothingWhenEveryAttemptCollides() throws Exception {
        int max = properties.maxAttempts();
        String[] codes = new String[max];
        for (int i = 0; i < max; i++) {
            codes[i] = "Full%03d".formatted(i + 1);
            data.seed(codes[i], "ACTIVE");
        }
        generator.willReturn(codes);
        String url = "https://example.com/ac7/" + UUID.randomUUID();

        HttpResponse<String> response = create(url);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = api.json(response);
        assertThat(body.path("errorCode").asText()).isEqualTo("SHORT_CODE_UNAVAILABLE");
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("uk_short_url")
                .doesNotContain("Full001");
        assertThat(generator.calls()).as("never more than the configured maximum").isEqualTo(max);
        assertThat(generator.pending()).isZero();
        assertThat(data.countByOriginalUrl(url)).as("no row committed for the marker").isZero();
        for (String code : codes) {
            assertThat(data.countByCode(code)).isEqualTo(1);
            assertThat(data.createdBy(code)).isEqualTo(ShortUrlTestData.SEED_OWNER);
        }

        // The pool and state are clean after exhaustion: the next create succeeds, and its marker
        // count (0 before, 1 after) shows the earlier zero was meaningful.
        HttpResponse<String> followUp = create(url);
        assertThat(followUp.statusCode()).isEqualTo(201);
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
    }

    // ---- AC15 ----

    @ParameterizedTest
    @ValueSource(strings = {"Health", "API", "v3", "LOGIN", "Static"})
    void shouldTreatReservedWordAsCollisionAndRetryWithoutTouchingTheDatabase(String reserved) throws Exception {
        generator.willReturn(reserved);
        String url = marker("ac15");

        HttpResponse<String> response = create(url);

        assertThat(response.statusCode()).isEqualTo(201);
        String code = api.json(response).path("shortCode").asText();
        assertThat(code).isNotEqualToIgnoringCase(reserved);
        assertThat(generator.calls()).isEqualTo(2);
        assertThat(data.countByCodeIgnoringCase(reserved)).as("the reserved code was never inserted").isZero();
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
    }

    @Test
    void shouldReturn503WhenEveryGeneratedCodeIsReservedAndInsertNothing() throws Exception {
        int max = properties.maxAttempts();
        String[] codes = new String[max];
        for (int i = 0; i < max; i++) {
            codes[i] = i % 2 == 0 ? "Health" : "ADMIN";
        }
        generator.willReturn(codes);
        String url = marker("ac15-all");

        HttpResponse<String> response = create(url);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("SHORT_CODE_UNAVAILABLE");
        assertThat(generator.calls()).isEqualTo(max);
        assertThat(data.countByOriginalUrl(url)).isZero();
        assertThat(data.countByCodeIgnoringCase("health") + data.countByCodeIgnoringCase("admin")).isZero();
    }
}
