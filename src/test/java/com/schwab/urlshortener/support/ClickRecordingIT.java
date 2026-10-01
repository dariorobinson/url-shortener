package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import com.schwab.urlshortener.util.validation.LocationEncoder;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-010 AC1, AC2, AC5 and AC6, D9, D45, D75, D76, D79, D91 through a real Tomcat and Testcontainers PostgreSQL.
 * The clock is the controllable {@link TestClock} (reset by {@link TestClockResetExtension}). Database state is
 * read directly, because the API cannot express "no row was written". Rows are truncated before each test only.
 */
class ClickRecordingIT extends IntegrationTestBase {

    private static final String CODE = "Click001";
    private static final String NANOS = "2026-03-01T10:15:30.123456789Z";
    private static final Instant MICROS = Instant.parse("2026-03-01T10:15:30.123456Z");
    private static final String NOT_FOUND = "SHORT_URL_NOT_FOUND";

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
    }

    private HttpResponse<String> call(String method, String path) throws Exception {
        return api.send(method, path, null, null, null);
    }

    private JsonNode details(String code) throws Exception {
        HttpResponse<String> response = api.send("GET", "/api/v1/urls/" + code, TestUsers.ALICE, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return api.json(response);
    }

    private void assertRedirect(HttpResponse<String> response, String location) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().allValues("Location")).containsExactly(location);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().firstValue("Pragma")).isEmpty();
        assertThat(response.headers().firstValue("Expires")).isEmpty();
        assertThat(response.body()).isEmpty();
    }

    // ---- AC1, D45 ----

    @Test
    void shouldCountOneClickAtTheTruncatedClockInstantAndLeaveVersionAndUpdatedAtAlone() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/clicked", TestUsers.ALICE);
        LifecycleState before = data.lifecycleState(CODE);
        assertThat(before.clickCount()).isZero();
        assertThat(before.lastAccessedAt()).isNull();
        assertThat(data.clickEventCount()).isZero();
        clock.setInstant(Instant.parse(NANOS));

        HttpResponse<String> served = call("GET", "/" + CODE);

        assertRedirect(served, "https://example.com/clicked");
        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.clickCount()).isEqualTo(1);
        // Truncated, not rounded: ...123456789Z would round to ...123457 if the database did the work (D45).
        assertThat(after.lastAccessedAt()).isEqualTo(MICROS);
        assertThat(data.clickEventCount()).isEqualTo(1);
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
        assertThat(data.clickedAts(CODE)).containsExactly(MICROS);
        Long eventShortUrlId = jdbc.queryForObject("SELECT short_url_id FROM click_event", Long.class);
        assertThat(eventShortUrlId).isEqualTo(data.shortUrlId(CODE));
        // AC6, D16, D27: the click never moves the optimistic-lock version or the management timestamp.
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.status()).isEqualTo(before.status());

        JsonNode view = details(CODE);
        assertThat(view.path("clickCount").asLong()).isEqualTo(1);
        assertThat(Instant.parse(view.path("lastAccessedAt").asText())).isEqualTo(MICROS);
    }

    @Test
    void shouldIncrementRatherThanSetAndKeepOneDistinctEventPerClick() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/twice", TestUsers.ALICE);
        clock.setInstant(Instant.parse(NANOS));
        assertThat(call("GET", "/" + CODE).statusCode()).isEqualTo(302);
        clock.advance(Duration.ofSeconds(5));
        assertThat(call("GET", "/" + CODE).statusCode()).isEqualTo(302);

        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.clickCount()).isEqualTo(2);
        assertThat(after.lastAccessedAt()).isEqualTo(MICROS.plusSeconds(5));
        assertThat(data.clickedAts(CODE)).containsExactly(MICROS, MICROS.plusSeconds(5));
        assertThat(details(CODE).path("clickCount").asLong()).isEqualTo(2);
    }

    @Test
    void shouldCountOnTopOfExistingClicksAndKeepPriorEventsUntouched() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/existing");
        data.seedClicks(CODE, 41, Instant.parse("2026-01-01T00:00:00Z"));
        clock.setInstant(Instant.parse(NANOS));

        assertThat(call("GET", "/" + CODE).statusCode()).isEqualTo(302);

        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.clickCount()).isEqualTo(42);
        assertThat(after.lastAccessedAt()).isEqualTo(MICROS);
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
    }

    @Test
    void shouldServeTheSameHeadersForAnAsciiGetAsForHeadAndCountOnlyTheGet() throws Exception {
        data.seed(CODE, "ACTIVE", "https://Example.COM/Path/a%2fb?a=1&b=two+words#top");

        HttpResponse<String> head = call("HEAD", "/" + CODE);
        HttpResponse<String> get = call("GET", "/" + CODE);

        assertRedirect(get, "https://Example.COM/Path/a%2fb?a=1&b=two+words#top");
        assertRedirect(head, "https://Example.COM/Path/a%2fb?a=1&b=two+words#top");
        assertThat(ApiClient.stableHeaders(get)).isEqualTo(ApiClient.stableHeaders(head));
        assertThat(data.lifecycleState(CODE).clickCount()).isEqualTo(1);
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
    }

    @Test
    void shouldPercentEncodeTheLocationOfANonAsciiTargetExactlyAsBeforeAndCountOnce() throws Exception {
        String stored = "https://example.com/café/中?q=ü";
        data.seed(CODE, "ACTIVE", stored);

        HttpResponse<String> get = call("GET", "/" + CODE);

        assertRedirect(get, "https://example.com/caf%C3%A9/%E4%B8%AD?q=%C3%BC");
        assertThat(get.headers().allValues("Location")).containsExactly(LocationEncoder.encode(stored));
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
    }

    @Test
    void shouldIgnoreTheQueryStringOfTheShortLinkAndCountOnce() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/plain");

        HttpResponse<String> get = call("GET", "/" + CODE + "?utm=secret&x=1");

        assertRedirect(get, "https://example.com/plain");
        assertThat(data.lifecycleState(CODE).clickCount()).isEqualTo(1);
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
    }

    @Test
    void shouldCountAnAuthenticatedGetToo() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/authed");

        HttpResponse<String> get = api.send("GET", "/" + CODE, TestUsers.BOB, null, null);

        assertRedirect(get, "https://example.com/authed");
        assertThat(data.lifecycleState(CODE).clickCount()).isEqualTo(1);
    }

    // ---- AC2, D9, D18 ----

    @Test
    void shouldRecordNothingForHeadWhileAGetOnTheSamePathIsCounted() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/head");
        LifecycleState before = data.lifecycleState(CODE);

        List<Integer> statuses = List.of(call("HEAD", "/" + CODE).statusCode(), call("HEAD", "/" + CODE).statusCode(),
                call("HEAD", "/" + CODE).statusCode());

        // Non-vacuity: the handler really ran for each HEAD.
        assertThat(statuses).containsExactly(302, 302, 302);
        assertThat(data.lifecycleState(CODE)).isEqualTo(before);
        assertThat(data.lifecycleState(CODE).clickCount()).isZero();
        assertThat(data.clickEventCount()).isZero();

        // Positive control: the same path, the same database checks, and a GET does count.
        assertThat(call("GET", "/" + CODE).statusCode()).isEqualTo(302);
        assertThat(data.lifecycleState(CODE).clickCount()).isEqualTo(1);
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
    }

    // ---- D91 / AC1 precondition: only ACTIVE links are counted ----

    @Test
    void shouldAnswer404AndWriteNothingForDeactivatedDeletedUnknownAndMalformedCodes() throws Exception {
        data.seed("Deact001", "ACTIVE", "https://example.com/d");
        data.setStatus("Deact001", "DEACTIVATED");
        data.seed("Delet001", "ACTIVE", "https://example.com/x");
        data.markDeleted("Delet001");
        data.seed(CODE, "ACTIVE", "https://example.com/control");
        Map<String, Object> deactivatedBefore = data.rowState("Deact001");
        Map<String, Object> deletedBefore = data.rowState("Delet001");

        for (String path : List.of("/Deact001", "/Delet001", "/Unknown0", "/ab", "/a_b")) {
            HttpResponse<String> response = call("GET", path);
            assertThat(response.statusCode()).as("GET %s", path).isEqualTo(404);
            assertThat(api.json(response).path("errorCode").asText()).as(path).isEqualTo(NOT_FOUND);
        }

        assertThat(data.rowState("Deact001")).isEqualTo(deactivatedBefore);
        assertThat(data.rowState("Delet001")).isEqualTo(deletedBefore);
        assertThat(data.clickEventCount()).isZero();
        // Positive control: an ACTIVE code in the same table does count.
        assertThat(call("GET", "/" + CODE).statusCode()).isEqualTo(302);
        assertThat(data.clickEventCount()).isEqualTo(1);
    }

    @Test
    void shouldAnswer401AndNotCountWhenTheCredentialsAreInvalid() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/creds");

        HttpResponse<String> response = api.send("GET", "/" + CODE, null, null, null, "Authorization",
                ApiClient.rawBasicHeader("alice", "not-the-password"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(data.lifecycleState(CODE).clickCount()).isZero();
        assertThat(data.clickEventCount()).isZero();
    }

    // ---- AC5, D8: no IP, user agent or referrer is stored, even when the client sends them ----

    @Test
    void shouldStoreOnlyIdShortUrlIdAndClickedAtEvenWhenTheClientSendsTrackingHeaders() throws Exception {
        data.seed(CODE, "ACTIVE", "https://example.com/tracking");

        HttpResponse<String> response = api.send("GET", "/" + CODE, null, null, null, "User-Agent", "ua-marker-u010",
                "Referer", "https://referrer.example/u010", "X-Forwarded-For", "203.0.113.77");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'click_event' ORDER BY ordinal_position",
                String.class)).containsExactly("id", "short_url_id", "clicked_at");
        List<String> rowText = jdbc.queryForList("SELECT e::text FROM click_event e", String.class);
        assertThat(rowText).hasSize(1);
        assertThat(rowText.get(0)).doesNotContain("ua-marker-u010").doesNotContain("203.0.113.77")
                .doesNotContain("referrer.example");
    }
}
