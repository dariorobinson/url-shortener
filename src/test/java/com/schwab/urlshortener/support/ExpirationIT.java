package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-016 AC1–AC7 and AC9 through a real Tomcat and Testcontainers PostgreSQL, with the controllable {@link TestClock}
 * fixed for every boundary. Links are created through the API; database state is read directly where the API cannot
 * express "nothing was written". Rows are truncated before each test only. Every 404 asserts its errorCode.
 */
class ExpirationIT extends IntegrationTestBase {

    private static final String BASE = "/api/v1/urls/";
    private static final String JSON = "application/json";
    /** Deliberately not on a microsecond boundary, so truncation is exercised on every create. */
    private static final Instant T0 = Instant.parse("2026-09-30T12:00:00.123456789Z");
    private static final Instant T0_MICROS = Instant.parse("2026-09-30T12:00:00.123456Z");
    private static final Instant EXPIRY = Instant.parse("2026-09-30T13:00:00Z");
    private static final String EXPIRY_TEXT = "2026-09-30T13:00:00Z";
    private static final String LATER_TEXT = "2026-10-31T00:00:00Z";
    private static final String TARGET = "https://example.com/expiring";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        clock.setInstant(T0);
    }

    private HttpResponse<String> create(String alias, String expiresAtJson) throws Exception {
        String body = "{\"originalUrl\":\"" + TARGET + "\",\"alias\":\"" + alias + "\""
                + (expiresAtJson == null ? "" : ",\"expiresAt\":" + expiresAtJson) + "}";
        return api.post(TestUsers.ALICE, body);
    }

    private void createExpiring(String alias) throws Exception {
        assertThat(create(alias, "\"" + EXPIRY_TEXT + "\"").statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> patch(String user, String code, String body) throws Exception {
        return api.send("PATCH", BASE + code, user, JSON, body);
    }

    private HttpResponse<String> redirect(String method, String code) throws Exception {
        return api.send(method, "/" + code, null, null, null);
    }

    private Instant storedExpiry(String code) {
        Timestamp value = jdbc.queryForObject("SELECT expires_at FROM short_url WHERE short_code = ?",
                Timestamp.class, code);
        return value == null ? null : value.toInstant();
    }

    private void assertGone(HttpResponse<String> response, boolean head) throws Exception {
        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        if (head) {
            assertThat(response.body()).isEmpty();
        } else {
            assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
            assertThat(api.json(response).path("errorCode").asText()).isEqualTo("SHORT_URL_EXPIRED");
        }
    }

    private void assertNotFound(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("SHORT_URL_NOT_FOUND");
    }

    // ---- AC1, AC7, AC9

    @Test
    void shouldCreateAnExpiringLinkAndReportExpiresAtAndExpired() throws Exception {
        HttpResponse<String> created = create("Exp0001", "\"2026-09-30T15:00:00+02:00\"");

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = api.json(created);
        assertThat(body.path("expiresAt").asText()).isEqualTo(EXPIRY_TEXT);
        assertThat(body.path("expired").asBoolean()).isFalse();
        assertThat(storedExpiry("Exp0001")).isEqualTo(EXPIRY);
    }

    @Test
    void shouldCreateANeverExpiringLinkWithANullExpiresAt() throws Exception {
        HttpResponse<String> created = create("Never01", null);

        JsonNode body = api.json(created);
        assertThat(body.has("expiresAt")).isTrue();
        assertThat(body.path("expiresAt").isNull()).isTrue();
        assertThat(body.path("expired").asBoolean()).isFalse();
        assertThat(storedExpiry("Never01")).isNull();
    }

    // ---- AC2

    @ParameterizedTest
    @ValueSource(strings = {"\"2026-09-30T11:59:59Z\"", "\"2026-09-30T12:00:00.123456Z\"",
            "\"2026-09-30T12:00:00.123456500Z\"", "\"2036-09-30T12:00:00.123457Z\""})
    void shouldRejectAPastPresentSubMicrosecondOrTooDistantExpiryWith400AndCreateNothing(String expiresAt)
            throws Exception {
        HttpResponse<String> response = create("Bad0001", expiresAt);

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode problem = api.json(response);
        assertThat(problem.path("errorCode").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.path("errors").get(0).path("field").asText()).isEqualTo("expiresAt");
        assertThat(response.body()).doesNotContain("2026-09-30T1").doesNotContain("2036");
        assertThat(data.countByCode("Bad0001")).isZero();
        // Positive control: the same request with a valid expiry is created.
        assertThat(create("Bad0001", "\"" + EXPIRY_TEXT + "\"").statusCode()).isEqualTo(201);
    }

    @Test
    void shouldAcceptOneMicrosecondAfterNowAndExactlyTheTenYearHorizon() throws Exception {
        assertThat(create("Edge001", "\"2026-09-30T12:00:00.123457Z\"").statusCode()).isEqualTo(201);
        assertThat(create("Edge002", "\"2036-09-30T12:00:00.123456Z\"").statusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1790000000", "\"2026-10-01T00:00:00\"", "\"2026-10-01\"", "\"\""})
    void shouldRejectNonStrictTimestampsWithMalformedRequestAndCreateNothing(String expiresAt) throws Exception {
        HttpResponse<String> response = create("Mal0001", expiresAt);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(data.countByCode("Mal0001")).isZero();
    }

    // ---- AC3

    @Test
    void shouldRedirectUntilTheExpiryInstantAndReturn410FromItOnGetAndHead() throws Exception {
        createExpiring("Exp0002");

        clock.setInstant(EXPIRY.minusNanos(1000));
        assertThat(redirect("GET", "Exp0002").statusCode()).isEqualTo(302);
        assertThat(redirect("HEAD", "Exp0002").statusCode()).isEqualTo(302);

        clock.setInstant(EXPIRY);
        assertGone(redirect("GET", "Exp0002"), false);
        assertGone(redirect("HEAD", "Exp0002"), true);

        clock.setInstant(EXPIRY.plusNanos(1000));
        assertGone(redirect("GET", "Exp0002"), false);
    }

    @Test
    void shouldNotCountRequestsAfterTheExpiry() throws Exception {
        createExpiring("Exp0003");
        clock.setInstant(EXPIRY.minusSeconds(1));
        assertThat(redirect("GET", "Exp0003").statusCode()).isEqualTo(302);
        assertThat(data.lifecycleState("Exp0003").clickCount()).isEqualTo(1);

        clock.setInstant(EXPIRY);
        assertGone(redirect("GET", "Exp0003"), false);
        assertGone(redirect("GET", "Exp0003"), false);

        assertThat(data.lifecycleState("Exp0003").clickCount()).isEqualTo(1);
        assertThat(data.clickEventCount("Exp0003")).isEqualTo(1);
    }

    // ---- AC4

    @Test
    void shouldReturn404ForADeactivatedOrDeletedLinkEvenIfItHasExpired() throws Exception {
        createExpiring("Deact01");
        createExpiring("Delet01");
        createExpiring("Activ01");
        assertThat(patch(TestUsers.ALICE, "Deact01", "{\"active\":false}").statusCode()).isEqualTo(200);
        assertThat(api.send("DELETE", BASE + "Delet01", TestUsers.ADMIN, null, null).statusCode()).isEqualTo(204);

        clock.setInstant(EXPIRY.plusSeconds(60));

        assertNotFound(redirect("GET", "Deact01"));
        assertNotFound(redirect("GET", "Delet01"));
        assertGone(redirect("GET", "Activ01"), false);
    }

    // ---- AC5

    @Test
    void shouldExtendShortenAndClearTheExpiry() throws Exception {
        createExpiring("Patch01");

        HttpResponse<String> extended = patch(TestUsers.ALICE, "Patch01", "{\"expiresAt\":\"" + LATER_TEXT + "\"}");
        assertThat(extended.statusCode()).isEqualTo(200);
        assertThat(api.json(extended).path("expiresAt").asText()).isEqualTo(LATER_TEXT);
        assertThat(storedExpiry("Patch01")).isEqualTo(Instant.parse(LATER_TEXT));

        HttpResponse<String> cleared = patch(TestUsers.ALICE, "Patch01", "{\"expiresAt\":null}");
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(api.json(cleared).path("expiresAt").isNull()).isTrue();
        assertThat(storedExpiry("Patch01")).isNull();
    }

    @Test
    void shouldWriteNothingWhenTheSameExpiryIsSent() throws Exception {
        createExpiring("Same001");
        LifecycleState before = data.lifecycleState("Same001");

        assertThat(patch(TestUsers.ALICE, "Same001", "{\"expiresAt\":\"" + EXPIRY_TEXT + "\"}").statusCode())
                .isEqualTo(200);
        assertThat(data.lifecycleState("Same001")).isEqualTo(before);

        // Positive control: a different value on the same row does write once.
        assertThat(patch(TestUsers.ALICE, "Same001", "{\"expiresAt\":\"" + LATER_TEXT + "\"}").statusCode())
                .isEqualTo(200);
        assertThat(data.lifecycleState("Same001").version()).isEqualTo(before.version() + 1);
    }

    @Test
    void shouldApplyNothingWhenAMixedPatchHasARedundantActive() throws Exception {
        createExpiring("Mixed01");
        LifecycleState before = data.lifecycleState("Mixed01");

        HttpResponse<String> response = patch(TestUsers.ALICE, "Mixed01",
                "{\"active\":true,\"expiresAt\":\"" + LATER_TEXT + "\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("SHORT_URL_ALREADY_ACTIVE");
        assertThat(storedExpiry("Mixed01")).isEqualTo(EXPIRY);
        assertThat(data.lifecycleState("Mixed01")).isEqualTo(before);
    }

    @Test
    void shouldRejectAPastExpiryOnPatchAndChangeNothing() throws Exception {
        createExpiring("Past001");
        LifecycleState before = data.lifecycleState("Past001");

        HttpResponse<String> response = patch(TestUsers.ALICE, "Past001", "{\"expiresAt\":\"2026-09-30T11:00:00Z\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(api.json(response).path("errors").get(0).path("field").asText()).isEqualTo("expiresAt");
        assertThat(data.lifecycleState("Past001")).isEqualTo(before);
        assertThat(storedExpiry("Past001")).isEqualTo(EXPIRY);
    }

    @Test
    void shouldReturn404ToANonOwnerAndChangeNothing() throws Exception {
        createExpiring("Owner01");

        assertNotFound(patch(TestUsers.BOB, "Owner01", "{\"expiresAt\":null}"));

        assertThat(storedExpiry("Owner01")).isEqualTo(EXPIRY);
        // Positive control: ADMIN may change any link's expiry (D4).
        assertThat(patch(TestUsers.ADMIN, "Owner01", "{\"expiresAt\":null}").statusCode()).isEqualTo(200);
        assertThat(storedExpiry("Owner01")).isNull();
    }

    // ---- AC6

    @Test
    void shouldReviveAnExpiredLinkByExtendingOrClearingIt() throws Exception {
        createExpiring("Rev0001");
        createExpiring("Rev0002");
        clock.setInstant(EXPIRY.plusSeconds(60));
        assertGone(redirect("GET", "Rev0001"), false);

        assertThat(patch(TestUsers.ALICE, "Rev0001", "{\"expiresAt\":\"" + LATER_TEXT + "\"}").statusCode())
                .isEqualTo(200);
        assertThat(patch(TestUsers.ALICE, "Rev0002", "{\"expiresAt\":null}").statusCode()).isEqualTo(200);

        assertThat(redirect("GET", "Rev0001").statusCode()).isEqualTo(302);
        assertThat(redirect("GET", "Rev0002").statusCode()).isEqualTo(302);
    }

    @Test
    void shouldNeverLetAnExpiredCodeBeTakenByANewLink() throws Exception {
        createExpiring("Reuse01");
        clock.setInstant(EXPIRY.plusSeconds(60));

        HttpResponse<String> again = create("Reuse01", null);

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(api.json(again).path("errorCode").asText()).isEqualTo("ALIAS_ALREADY_EXISTS");
        assertThat(data.countByCode("Reuse01")).isEqualTo(1);
    }

    // ---- AC7

    @Test
    void shouldStillShowDetailsAndStatsOfAnExpiredLinkWithExpiredTrue() throws Exception {
        createExpiring("Stat001");
        clock.setInstant(EXPIRY.minusSeconds(1));
        assertThat(redirect("GET", "Stat001").statusCode()).isEqualTo(302);
        clock.setInstant(EXPIRY.plusSeconds(60));

        HttpResponse<String> details = api.send("GET", BASE + "Stat001", TestUsers.ALICE, null, null);
        HttpResponse<String> stats = api.send("GET", BASE + "Stat001/stats", TestUsers.ALICE, null, null);

        assertThat(details.statusCode()).isEqualTo(200);
        assertThat(api.json(details).path("expired").asBoolean()).isTrue();
        assertThat(api.json(details).path("expiresAt").asText()).isEqualTo(EXPIRY_TEXT);
        assertThat(stats.statusCode()).isEqualTo(200);
        assertThat(api.json(stats).path("expired").asBoolean()).isTrue();
        assertThat(api.json(stats).path("totalClicks").asLong()).isEqualTo(1);
        // Positive control: the same details one second before the expiry say expired=false.
        clock.setInstant(EXPIRY.minusSeconds(1));
        assertThat(api.json(api.send("GET", BASE + "Stat001", TestUsers.ALICE, null, null)).path("expired")
                .asBoolean()).isFalse();
    }

    @Test
    void shouldStoreCreatedAtTruncatedAndTheExpiryStrictlyAfterIt() throws Exception {
        // AC9 through the API: the link is created at a non-microsecond instant and the V3 CHECK holds.
        createExpiring("Trunc01");

        assertThat(data.createdAt("Trunc01")).isEqualTo(T0_MICROS);
        assertThat(storedExpiry("Trunc01")).isAfter(data.createdAt("Trunc01"));
    }
}
