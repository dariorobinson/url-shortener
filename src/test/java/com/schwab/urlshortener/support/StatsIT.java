package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schwab.urlshortener.service.StatsPeriod;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-011 {@code GET /api/v1/urls/{code}/stats} through a real Tomcat and Testcontainers PostgreSQL: the daily
 * bucketing across DST and zone transitions (D95), the parameter rules (D96 to D100), the response (D101), the
 * REPEATABLE READ snapshot (D102), the precedence of 400 over 404 (D104), DEACTIVATED visibility (D105) and the
 * D94 last-access rule. Click events are seeded bound as UTC {@link OffsetDateTime}, never as a timestamp, so the
 * JVM zone cannot shift the fall-back hour. "Today" comes from the {@link TestClock}, never from a zone on it.
 * A {@code +} in a query string is written as {@code %2B}; the unencoded forms are tested on purpose and named so.
 */
@ExtendWith(OutputCaptureExtension.class)
class StatsIT extends IntegrationTestBase {

    private static final String BASE = "/api/v1/urls/";
    private static final String CODE = "Stats001";
    private static final String NOT_FOUND = "SHORT_URL_NOT_FOUND";
    private static final String NO_STORE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final Set<String> STATS_KEYS = Set.of("shortCode", "timezone", "from", "to", "totalClicks",
            "clicksInRange", "lastAccessedAt", "daily",
            "expiresAt", "expired");
    private static final Set<String> PROBLEM_KEYS = Set.of("type", "title", "status", "detail", "instance",
            "errorCode");
    private static final Set<String> VALIDATION_KEYS = Set.of("type", "title", "status", "detail", "instance",
            "errorCode", "errors");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestClock clock;

    private ApiClient api;
    private ShortUrlTestData data;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        pool = Executors.newCachedThreadPool();
        data.seed(CODE, "ACTIVE", "https://example.com/stats", TestUsers.ALICE);
        data.seed("AliceAct1", "ACTIVE", "https://example.com/alice-active", TestUsers.ALICE);
        data.seed("AliceDea1", "DEACTIVATED", "https://example.com/alice-paused", TestUsers.ALICE);
        data.seed("AliceDel1", "ACTIVE", "https://example.com/alice-gone", TestUsers.ALICE);
        data.markDeleted("AliceDel1");
        data.seed("BobAct1", "ACTIVE", "https://example.com/bob-active", TestUsers.BOB);
        data.seed("AdminAct1", "ACTIVE", "https://example.com/admin-active", TestUsers.ADMIN);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ---- helpers ----

    private HttpResponse<String> send(String method, String user, String pathAndQuery, String... headers)
            throws Exception {
        return api.send(method, pathAndQuery, user, null, null, headers);
    }

    private HttpResponse<String> stats(String user, String code, String query) throws Exception {
        return send("GET", user, BASE + code + "/stats" + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    private HttpResponse<String> alice(String query) throws Exception {
        return stats(TestUsers.ALICE, CODE, query);
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
        JsonNode body = api.json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(STATS_KEYS);
        return body;
    }

    private static List<Long> counts(JsonNode body) {
        List<Long> counts = new ArrayList<>();
        body.path("daily").forEach(d -> counts.add(d.path("clicks").asLong(-1)));
        return counts;
    }

    /** One entry per date from..to, ascending, each {date, clicks}, and clicksInRange equal to the sum. */
    private static void assertDenseAndConsistent(JsonNode body) {
        LocalDate date = LocalDate.parse(body.path("from").asText());
        long sum = 0;
        for (JsonNode day : body.path("daily")) {
            assertThat(ApiClient.keys(day)).containsExactly("clicks", "date");
            assertThat(LocalDate.parse(day.path("date").asText())).isEqualTo(date);
            date = date.plusDays(1);
            sum += day.path("clicks").asLong();
        }
        assertThat(date.minusDays(1)).isEqualTo(LocalDate.parse(body.path("to").asText()));
        assertThat(body.path("clicksInRange").asLong(-1)).isEqualTo(sum);
    }

    /** 400 VALIDATION_FAILED whose errors are exactly these fields, sorted, each with a fixed rule text. */
    private void assertValidation(HttpResponse<String> response, String... fields) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = api.json(response);
        assertThat(body.path("errorCode").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(ApiClient.keys(body)).isEqualTo(VALIDATION_KEYS);
        List<String> actual = new ArrayList<>();
        body.path("errors").forEach(e -> {
            assertThat(ApiClient.keys(e)).containsExactly("field", "message");
            actual.add(e.path("field").asText());
            assertThat(e.path("message").asText()).isIn(StatsPeriod.TIMEZONE_RULE, StatsPeriod.DATE_RULE,
                    StatsPeriod.ORDER_RULE, StatsPeriod.LENGTH_RULE);
        });
        assertThat(actual).containsExactly(fields);
        assertThat(body.path("instance").asText()).doesNotContain("?");
    }

    private void assertMalformed(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode body = api.json(response);
        assertThat(body.path("errorCode").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
    }

    private void assertNotFound(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode body = api.json(response);
        assertThat(body.path("errorCode").asText()).isEqualTo(NOT_FOUND);
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
    }

    private static Instant lastAccess(JsonNode body) {
        return Instant.parse(body.path("lastAccessedAt").asText());
    }

    private static Instant startOf(String date, ZoneId zone) {
        return LocalDate.parse(date).atStartOfDay(zone).toInstant();
    }

    private static Instant[] instants(String... values) {
        return Arrays.stream(values).map(Instant::parse).toArray(Instant[]::new);
    }

    // ---- AC1, AC2, AC8, AC10: bucketing across DST and zone transitions (D95) ----

    /** A zone, a window, click instants at the exact day edges, and the expected counts per zone day and UTC day. */
    record Bucketing(String name, String zone, String from, String to, String[] clicks, List<Long> zoneCounts,
            List<Long> utcCounts) {

        /** The expected counts computed by java.time alone, to prove the hard-coded numbers are right. */
        List<Long> model(ZoneId bucketZone) {
            LocalDate start = LocalDate.parse(from);
            long days = LocalDate.parse(to).toEpochDay() - start.toEpochDay() + 1;
            List<Long> model = new ArrayList<>();
            for (int i = 0; i < days; i++) {
                LocalDate day = start.plusDays(i);
                model.add(Arrays.stream(clicks).map(Instant::parse)
                        .filter(c -> LocalDate.ofInstant(c, bucketZone).equals(day)).count());
            }
            return model;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Bucketing> bucketings() {
        return Stream.of(
                new Bucketing("New York spring forward, a 23-hour day", "America/New_York", "2026-03-07", "2026-03-09",
                        new String[] {"2026-03-08T04:59:59.999999Z", "2026-03-08T05:00:00Z",
                            "2026-03-08T06:59:59.999999Z", "2026-03-08T07:00:00Z", "2026-03-09T03:59:59.999999Z",
                            "2026-03-09T04:00:00Z"},
                        List.of(1L, 4L, 1L), List.of(0L, 4L, 2L)),
                new Bucketing("New York fall back, a 25-hour day", "America/New_York", "2026-10-31", "2026-11-02",
                        new String[] {"2026-11-01T03:59:59.999999Z", "2026-11-01T04:00:00Z", "2026-11-01T05:30:00Z",
                            "2026-11-01T06:30:00Z", "2026-11-02T04:30:00Z", "2026-11-02T05:00:00Z"},
                        List.of(1L, 4L, 1L), List.of(0L, 4L, 2L)),
                new Bucketing("Sydney spring forward, a 23-hour day", "Australia/Sydney", "2026-10-03", "2026-10-05",
                        new String[] {"2026-10-03T13:59:59.999999Z", "2026-10-03T14:00:00Z",
                            "2026-10-03T15:59:59.999999Z", "2026-10-03T16:00:00Z", "2026-10-04T12:59:59.999999Z",
                            "2026-10-04T13:00:00Z"},
                        List.of(1L, 4L, 1L), List.of(4L, 2L, 0L)),
                new Bucketing("Sydney fall back, a 25-hour day", "Australia/Sydney", "2026-04-04", "2026-04-06",
                        new String[] {"2026-04-04T12:59:59.999999Z", "2026-04-04T13:00:00Z", "2026-04-04T15:30:00Z",
                            "2026-04-04T16:30:00Z", "2026-04-05T13:59:59.999999Z", "2026-04-05T14:00:00Z"},
                        List.of(1L, 4L, 1L), List.of(4L, 2L, 0L)),
                new Bucketing("Sao Paulo, a day that starts after a midnight gap", "America/Sao_Paulo", "2018-11-03",
                        "2018-11-05",
                        new String[] {"2018-11-04T02:59:59.999999Z", "2018-11-04T03:00:00Z",
                            "2018-11-05T01:59:59.999999Z", "2018-11-05T02:00:00Z"},
                        List.of(1L, 2L, 1L), List.of(0L, 2L, 2L)),
                new Bucketing("Samoa, a whole date skipped", "Pacific/Apia", "2011-12-29", "2011-12-31",
                        new String[] {"2011-12-29T09:59:59.999999Z", "2011-12-29T10:00:00Z",
                            "2011-12-30T09:59:59.999999Z", "2011-12-30T10:00:00Z", "2011-12-31T09:59:59.999999Z",
                            "2011-12-31T10:00:00Z"},
                        List.of(2L, 0L, 2L), List.of(2L, 2L, 2L)),
                new Bucketing("Kathmandu, a +05:45 offset", "Asia/Kathmandu", "2026-03-01", "2026-03-02",
                        new String[] {"2026-02-28T18:14:59.999999Z", "2026-02-28T18:15:00Z", "2026-03-01T18:14:59Z",
                            "2026-03-01T18:15:00Z"},
                        List.of(2L, 1L), List.of(2L, 0L)));
    }

    @ParameterizedTest
    @MethodSource("bucketings")
    void shouldBucketByLocalDayInTheRequestedZoneAndByUtcDayWhenNoZoneIsGiven(Bucketing fixture) throws Exception {
        // The hard-coded expectations are checked against java.time first, so a wrong fixture cannot pass.
        assertThat(fixture.model(ZoneId.of(fixture.zone))).isEqualTo(fixture.zoneCounts);
        assertThat(fixture.model(ZoneOffset.UTC)).isEqualTo(fixture.utcCounts);
        data.seedClickEvents(CODE, instants(fixture.clicks));
        String window = "from=" + fixture.from + "&to=" + fixture.to;

        JsonNode zoned = ok(alice("timezone=" + fixture.zone + "&" + window));
        JsonNode utc = ok(alice(window));
        JsonNode explicitUtc = ok(alice("timezone=UTC&" + window));

        assertThat(counts(zoned)).isEqualTo(fixture.zoneCounts);
        assertThat(zoned.path("timezone").asText()).isEqualTo(fixture.zone);
        assertThat(counts(utc)).isEqualTo(fixture.utcCounts);
        assertThat(utc.path("timezone").asText()).isEqualTo("UTC");
        assertThat(explicitUtc).isEqualTo(utc);
        for (JsonNode body : List.of(zoned, utc)) {
            assertDenseAndConsistent(body);
            assertThat(body.path("from").asText()).isEqualTo(fixture.from);
            assertThat(body.path("to").asText()).isEqualTo(fixture.to);
            assertThat(body.path("totalClicks").asLong()).isEqualTo(fixture.clicks.length);
        }
        assertThat(zoned.path("clicksInRange").asLong()).isEqualTo(fixture.zoneCounts.stream().mapToLong(l -> l).sum());
    }

    @ParameterizedTest
    @CsvSource({
            // zone, the DST day used as both from and to, and the four clicks that fall on it
            "America/New_York,2026-03-08,4", "America/New_York,2026-11-01,4",
            "Australia/Sydney,2026-10-04,4", "Australia/Sydney,2026-04-05,4"})
    void shouldCountOnlyTheClicksOfAOneDayWindowOnADstDayExcludingBothEdgesNeighbours(String zone, String day,
            long expected) throws Exception {
        Bucketing fixture = bucketings().filter(b -> b.zone.equals(zone)).filter(b -> b.from.compareTo(day) <= 0
                && b.to.compareTo(day) >= 0).findFirst().orElseThrow();
        data.seedClickEvents(CODE, instants(fixture.clicks));

        JsonNode body = ok(alice("timezone=" + zone + "&from=" + day + "&to=" + day));

        assertThat(counts(body)).containsExactly(expected);
        assertThat(body.path("totalClicks").asLong()).isEqualTo(6);
        assertDenseAndConsistent(body);
    }

    @Test
    void shouldUseTheHardCodedDayStartsOfTheDesignForNewYork() {
        // Guards the fixtures themselves: the spring day is 23 hours, the fall day 25 hours (D95).
        ZoneId ny = ZoneId.of("America/New_York");
        assertThat(startOf("2026-03-08", ny)).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
        assertThat(startOf("2026-03-09", ny)).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
        assertThat(startOf("2026-11-01", ny)).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));
        assertThat(startOf("2026-11-02", ny)).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
    }

    @Test
    void shouldListAZeroClickDayBetweenTwoDaysWithClicks() throws Exception {
        data.seedClickEvents(CODE, instants("2026-03-01T12:00:00Z", "2026-03-03T12:00:00Z"));

        JsonNode body = ok(alice("from=2026-03-01&to=2026-03-03"));

        assertThat(counts(body)).containsExactly(1L, 0L, 1L);
        assertThat(body.path("daily").get(1).path("date").asText()).isEqualTo("2026-03-02");
        assertDenseAndConsistent(body);
    }

    @Test
    void shouldBucketEtcGmtPlusFiveAtMinusFiveAndEtcGmtMinusFiveAtPlusFive() throws Exception {
        // D96: the IANA sign of Etc/GMT+5 is UTC-5. Java applies tzdb's own rules, so the buckets are right.
        data.seedClickEvents(CODE, instants("2026-03-02T04:59:59Z", "2026-03-02T05:00:00Z", "2026-03-01T18:59:59Z",
                "2026-03-01T19:00:00Z"));

        JsonNode minusFive = ok(alice("timezone=Etc/GMT%2B5&from=2026-03-01&to=2026-03-02"));
        JsonNode plusFive = ok(alice("timezone=Etc/GMT-5&from=2026-03-01&to=2026-03-02"));

        assertThat(minusFive.path("timezone").asText()).isEqualTo("Etc/GMT+5");
        assertThat(counts(minusFive)).containsExactly(3L, 1L);
        assertThat(plusFive.path("timezone").asText()).isEqualTo("Etc/GMT-5");
        // +5: 18:59:59Z is 23:59:59 on 1 March, 19:00:00Z is 00:00 on 2 March; 04:59:59Z and 05:00:00Z are on 2 March.
        assertThat(counts(plusFive)).containsExactly(1L, 3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "Asia/Calcutta", "Asia/Kolkata", "US/Eastern", "GMT", "Etc/UTC",
            "EST5EDT", "Asia/Kathmandu", "Australia/Lord_Howe", "Etc/GMT%2B5", "Etc/GMT-14"})
    void shouldAcceptEveryIanaIdOfTheJdkSet(String zone) throws Exception {
        JsonNode body = ok(alice("timezone=" + zone));

        assertThat(body.path("timezone").asText()).isEqualTo(zone.replace("%2B", "+"));
        assertThat(body.path("daily")).hasSize(30);
    }

    // ---- AC3: rejected zones (D96, D99) ----

    @ParameterizedTest
    @ValueSource(strings = {"%2B05:00", "%2B0530", "-03", "Z", "UTC%2B5", "UTC%2B05:00", "GMT-3", "UT", "UT%2B1",
            "PST", "IST", "CTT", "america/new_york", "AMERICA/NEW_YORK", "utc", "", "%20UTC", "UTC%20",
            "America/New%20York", "Mars/Olympus_Mons", "../../etc/passwd", "%C3%A9", "UTC;DROP%20TABLE%20x"})
    void shouldRejectEveryNonIanaZoneWithOneTimezoneViolationAndNeverEchoTheValue(String zone) throws Exception {
        HttpResponse<String> response = alice("timezone=" + zone);

        assertValidation(response, "timezone");
        JsonNode body = api.json(response);
        assertThat(body.path("errors").get(0).path("message").asText()).isEqualTo(StatsPeriod.TIMEZONE_RULE);
        assertThat(body.path("detail").asText()).isEqualTo("The query parameters failed validation.");
        String echoed = URLDecoder.decode(zone, StandardCharsets.UTF_8);
        if (echoed.length() > 2) {
            String stripped = response.body().replace(StatsPeriod.TIMEZONE_RULE, "");
            assertThat(stripped).doesNotContain(echoed);
        }
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("at com.schwab")
                .doesNotContain("INVALID_TIMEZONE").doesNotContain("ZoneRules");
    }

    @Test
    void shouldAcceptEveryIdOfTheJdkZoneRulesProviderAndEchoItExactly() throws Exception {
        // D96: the accepted set is exactly UTC plus ZoneRulesProvider.getAvailableZoneIds(), each sent URL-encoded.
        List<String> failures = new ArrayList<>();
        Set<String> ids = new TreeSet<>(ZoneRulesProvider.getAvailableZoneIds());
        ids.add("UTC");
        for (String id : ids) {
            String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8);
            HttpResponse<String> response = alice("timezone=" + encoded + "&from=2026-03-01&to=2026-03-01");
            if (response.statusCode() != 200 || !id.equals(api.json(response).path("timezone").asText())) {
                failures.add(id + " -> " + response.statusCode());
            }
        }
        assertThat(ids.size()).isGreaterThan(400);
        assertThat(failures).isEmpty();
    }

    @Test
    void shouldRejectEstBecauseTheJdkLeavesItOutOfTheTzdbSetAlthoughPostgresqlWouldKnowIt() throws Exception {
        // EST, MST and HST are JDK short IDs only (ZoneId.SHORT_IDS), not members of the tzdb set.
        assertThat(ZoneRulesProvider.getAvailableZoneIds()).doesNotContain("EST", "MST", "HST");
        for (String id : List.of("EST", "MST", "HST")) {
            assertValidation(alice("timezone=" + id), "timezone");
        }
    }

    @Test
    void shouldRejectALongZoneWithoutEchoingIt() throws Exception {
        HttpResponse<String> response = alice("timezone=" + "a".repeat(5_000));

        assertValidation(response, "timezone");
        assertThat(response.body()).doesNotContain("aaaaaaaaaa");
    }

    @Test
    void shouldRefuseAZoneBeyondTheConnectorLimitWithoutEchoingItOrLeakingInternals() throws Exception {
        // 10 000 characters exceed Tomcat's 8 KiB request-line limit, so the connector answers before the app.
        HttpResponse<String> response = alice("timezone=" + "a".repeat(10_000));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("aaaaaaaaaa").doesNotContain("Exception")
                .doesNotContain("org.apache").doesNotContain("at com.schwab");
    }

    @Test
    void shouldReadAnUnencodedPlusAsASpaceSoAnUnencodedOffsetIsStillRejected() throws Exception {
        // MockMvc cannot show this: over real HTTP the servlet container decodes '+' in a query string as a space.
        assertValidation(alice("timezone=+05:00"), "timezone");
        assertValidation(alice("timezone=%2005:00"), "timezone");
        // Contrast: the valid ID Etc/GMT+5 is accepted only when its plus is encoded. Unencoded, it arrives as
        // "Etc/GMT 5", which is not an ID.
        assertValidation(alice("timezone=Etc/GMT+5"), "timezone");
        assertThat(ok(alice("timezone=Etc/GMT%2B5")).path("timezone").asText()).isEqualTo("Etc/GMT+5");
        assertValidation(alice("timezone=UTC+5"), "timezone");
    }

    // ---- AC9: defaults from the TestClock (D98) ----

    @Test
    void shouldDefaultToTheLastThirtyDaysEndingTodayInTheResolvedZone() throws Exception {
        clock.setInstant(Instant.parse("2026-03-10T03:30:00Z"));

        JsonNode utc = ok(alice(null));
        JsonNode newYork = ok(alice("timezone=America/New_York"));
        JsonNode tokyo = ok(alice("timezone=Asia/Tokyo"));

        assertThat(utc.path("from").asText()).isEqualTo("2026-02-09");
        assertThat(utc.path("to").asText()).isEqualTo("2026-03-10");
        assertThat(utc.path("daily")).hasSize(30);
        assertThat(newYork.path("from").asText()).isEqualTo("2026-02-08");
        assertThat(newYork.path("to").asText()).isEqualTo("2026-03-09");
        assertThat(newYork.path("daily")).hasSize(30);
        assertThat(tokyo.path("to").asText()).isEqualTo("2026-03-10");
        for (JsonNode body : List.of(utc, newYork, tokyo)) {
            assertDenseAndConsistent(body);
        }
    }

    @Test
    void shouldApplyEachDefaultIndependentlyOfTheOther() throws Exception {
        clock.setInstant(Instant.parse("2026-03-10T03:30:00Z"));

        JsonNode fromOnly = ok(alice("from=2026-03-01"));
        JsonNode toOnly = ok(alice("to=2026-03-01"));

        assertThat(fromOnly.path("from").asText()).isEqualTo("2026-03-01");
        assertThat(fromOnly.path("to").asText()).isEqualTo("2026-03-10");
        assertThat(fromOnly.path("daily")).hasSize(10);
        assertThat(toOnly.path("from").asText()).isEqualTo("2026-01-31");
        assertThat(toOnly.path("to").asText()).isEqualTo("2026-03-01");
        assertThat(toOnly.path("daily")).hasSize(30);
    }

    @Test
    void shouldFollowTheClockAcrossMidnightInTheResolvedZone() throws Exception {
        // New York is on EDT (UTC-4) from 8 March, so local midnight of 10 March is 04:00Z.
        clock.setInstant(Instant.parse("2026-03-10T03:59:59.999999Z"));
        assertThat(ok(alice("timezone=America/New_York")).path("to").asText()).isEqualTo("2026-03-09");
        clock.setInstant(Instant.parse("2026-03-10T04:00:00Z"));
        assertThat(ok(alice("timezone=America/New_York")).path("to").asText()).isEqualTo("2026-03-10");
        clock.setInstant(Instant.parse("2026-03-11T03:59:59.999999Z"));
        assertThat(ok(alice("timezone=America/New_York")).path("to").asText()).isEqualTo("2026-03-10");
        clock.setInstant(Instant.parse("2026-03-11T04:00:00Z"));
        assertThat(ok(alice("timezone=America/New_York")).path("to").asText()).isEqualTo("2026-03-11");
    }

    // ---- AC10, AC11, AC12, AC13: the window (D97, D98) ----

    @Test
    void shouldReturnOneEntryForASingleDayAndZerosForAFutureWindow() throws Exception {
        data.seedClickEvents(CODE, instants("2026-03-01T12:00:00Z", "2026-03-01T23:59:59.999999Z",
                "2026-03-02T00:00:00Z"));

        JsonNode single = ok(alice("from=2026-03-01&to=2026-03-01"));
        JsonNode future = ok(alice("from=2099-12-30&to=2099-12-31"));
        JsonNode farFuture = ok(alice("from=9999-12-31&to=9999-12-31&timezone=Pacific/Kiritimati"));

        assertThat(counts(single)).containsExactly(2L);
        assertThat(counts(future)).containsExactly(0L, 0L);
        assertThat(future.path("totalClicks").asLong()).isEqualTo(3);
        assertThat(counts(farFuture)).containsExactly(0L);
    }

    @Test
    void shouldAcceptTheLowerBoundDateOfTheRange() throws Exception {
        JsonNode body = ok(alice("from=1970-01-01&to=1970-01-02&timezone=Etc/GMT-14"));

        assertThat(counts(body)).containsExactly(0L, 0L);
    }

    @Test
    void shouldAcceptExactly366DaysAndRefuse367() throws Exception {
        JsonNode leapYear = ok(alice("from=2028-01-01&to=2028-12-31"));
        assertThat(leapYear.path("daily")).hasSize(366);
        assertDenseAndConsistent(leapYear);

        assertValidation(alice("from=2027-12-31&to=2028-12-31"), "from");
        assertValidation(alice("from=2025-01-01&to=2026-01-02"), "from");
        assertThat(api.json(alice("from=2027-12-31&to=2028-12-31")).path("errors").get(0).path("message").asText())
                .isEqualTo(StatsPeriod.LENGTH_RULE);
    }

    @Test
    void shouldRefuseFromAfterToOnFromAndNeverRevealAnyClickData() throws Exception {
        HttpResponse<String> response = alice("from=2026-03-09&to=2026-03-07");

        assertValidation(response, "from");
        assertThat(api.json(response).path("errors").get(0).path("message").asText()).isEqualTo(StatsPeriod.ORDER_RULE);
    }

    @ParameterizedTest
    @CsvSource({
            "from=2026-02-30,from", "from=2026-2-3,from", "from=20260203,from", "from=2026-02-03T00:00,from",
            "from=%2B2026-02-03,from", "from=%202026-02-03,from", "from=,from", "from=1969-12-31&to=1970-01-02,from",
            "from=0000-01-01&to=1970-01-02,from", "from=abc,from", "from=2026-13-01,from",
            "from=%D9%A2%D9%A0%D9%A2%D9%A6-%D9%A0%D9%A3-%D9%A0%D9%A1,from",
            "to=2026-02-30,to", "to=2026-2-3,to", "to=20260203,to", "to=2026-02-03T00:00,to", "to=,to",
            "to=10000-01-01,to", "to=%2B10000-01-01,to", "to=9999-12-32,to", "from=2026-02-01&to=2026-2-3,to"})
    void shouldRefuseMalformedOrOutOfRangeDatesNamingTheOffendingParameter(String query, String field)
            throws Exception {
        HttpResponse<String> response = alice(query);

        assertValidation(response, field);
        assertThat(api.json(response).path("errors").get(0).path("message").asText()).isEqualTo(StatsPeriod.DATE_RULE);
        String rawValue = query.substring(query.lastIndexOf('=') + 1);
        if (rawValue.length() > 4 && !StatsPeriod.DATE_RULE.contains(rawValue)) {
            assertThat(response.body()).doesNotContain(URLDecoder.decode(rawValue,
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldReportEveryBadParameterSortedByFieldAndSkipTheRangeChecksWhileOneIsInvalid() throws Exception {
        assertValidation(alice("to=x&from=y&timezone=PST"), "from", "timezone", "to");
        assertValidation(alice("from=2026-03-09&to=bad"), "to");
        assertValidation(alice("from=2020-01-01&to=2026-01-01&timezone=PST"), "timezone");
        // The order and length checks run only once all three values are valid (D98), so only the zone is reported.
        assertValidation(alice("timezone=PST&from=2026-03-09&to=2026-03-07"), "timezone");
    }

    // ---- AC14: unknown or repeated parameters (D100) ----

    @ParameterizedTest
    @ValueSource(strings = {"timeZone=UTC", "Timezone=UTC", "TIMEZONE=UTC", "tz=UTC", "zone=UTC", "cache=1",
            "timezone=UTC&timezone=UTC", "timezone=UTC&timezone=Asia/Tokyo", "from=2026-03-01&from=2026-03-01",
            "to=2026-03-01&to=2026-03-02", "timezone=UTC&cache=1", "timezone&tz", "timezone=PST&tz=UTC",
            "from=bad&from=bad"})
    void shouldRefuseAnUnknownOrRepeatedParameterAsMalformedWithoutEchoingTheName(String query) throws Exception {
        HttpResponse<String> response = alice(query);

        assertMalformed(response);
        assertThat(response.body()).doesNotContain("cache").doesNotContain("zone=").doesNotContain("Timezone")
                .doesNotContain("timeZone").doesNotContain("tz");
        assertThat(response.body()).doesNotContain("VALIDATION_FAILED");
    }

    @Test
    void shouldAnswerAKnownParameterWithoutAValueAsAValidationErrorNotAsMalformed() throws Exception {
        assertValidation(alice("timezone"), "timezone");
    }

    // ---- AC15: 400 before 404 (D104, D74) ----

    @Test
    void shouldRefuseBadParametersBeforeLookingTheLinkUpAndGiveTheIdentical404OnceTheyAreValid() throws Exception {
        HttpResponse<String> reference = stats(TestUsers.BOB, "Nothing9", "timezone=UTC");
        assertNotFound(reference);
        // Positive control: the same bad parameters are also 400 for the owner of a visible link.
        assertValidation(alice("timezone=PST"), "timezone");

        for (String code : List.of(CODE, "AliceDea1", "AliceDel1", "Nothing9", "ab", "a_b", "a".repeat(33))) {
            assertValidation(stats(TestUsers.BOB, code, "timezone=PST"), "timezone");
            assertValidation(stats(TestUsers.BOB, code, "from=2026-03-09&to=2026-03-07"), "from");
            assertMalformed(stats(TestUsers.BOB, code, "tz=UTC"));

            HttpResponse<String> notFound = stats(TestUsers.BOB, code, "timezone=UTC");
            assertNotFound(notFound);
            JsonNode expected = api.json(reference).deepCopy();
            JsonNode actual = api.json(notFound).deepCopy();
            ((ObjectNode) expected).remove("instance");
            ((ObjectNode) actual).remove("instance");
            assertThat(actual).as(code).isEqualTo(expected);
            assertThat(ApiClient.stableHeaders(notFound).keySet())
                    .isEqualTo(ApiClient.stableHeaders(reference).keySet());
        }
    }

    @Test
    void shouldRefuseBadParametersForAnAdminOnADeletedLinkBeforeTheNotFound() throws Exception {
        data.markDeleted(CODE);

        assertValidation(stats(TestUsers.ADMIN, CODE, "timezone=PST"), "timezone");
        assertNotFound(stats(TestUsers.ADMIN, CODE, "timezone=UTC"));
    }

    // ---- AC4, AC5, AC6, AC7, AC16: access (D4, D13, D31, D74, D105) ----

    @ParameterizedTest
    @CsvSource({
            "alice,AliceAct1,200", "alice,AliceDea1,200", "alice,AliceDel1,404", "alice,BobAct1,404",
            "alice,AdminAct1,404", "alice,Nothing9,404",
            "bob,AliceAct1,404", "bob,AliceDea1,404", "bob,AliceDel1,404", "bob,BobAct1,200", "bob,AdminAct1,404",
            "admin,AliceAct1,200", "admin,AliceDea1,200", "admin,AliceDel1,404", "admin,BobAct1,200",
            "admin,AdminAct1,200", "admin,Nothing9,404"})
    void shouldApplyTheOwnerAdminAndHiddenRulesToStats(String user, String code, int expected) throws Exception {
        HttpResponse<String> response = stats(user, code, null);

        assertThat(response.statusCode()).isEqualTo(expected);
        if (expected == 200) {
            ok(response);
            assertThat(api.json(response).path("shortCode").asText()).isEqualTo(code);
        } else {
            assertNotFound(response);
            assertThat(response.body()).doesNotContain("totalClicks").doesNotContain("example.com");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "a-b", "a_b", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ab%20c", "abc%C3%A9", "%41%42"})
    void shouldGive404ForMalformedCodesWithTheSameBodyAsAnUnknownCode(String code) throws Exception {
        HttpResponse<String> reference = stats(TestUsers.ALICE, "Nothing9", null);
        HttpResponse<String> response = stats(TestUsers.ALICE, code, null);

        assertNotFound(response);
        assertThat(api.json(response).path("detail")).isEqualTo(api.json(reference).path("detail"));
        assertThat(api.json(response).path("title")).isEqualTo(api.json(reference).path("title"));
    }

    @Test
    void shouldGiveIdenticalNotFoundBodiesForNotYoursDeletedAndUnknownOnTheSamePath() throws Exception {
        String code = "Same1234";
        HttpResponse<String> absent = stats(TestUsers.BOB, code, null);
        data.seed(code, "ACTIVE", "https://example.com/same", TestUsers.ALICE);
        HttpResponse<String> foreign = stats(TestUsers.BOB, code, null);
        assertThat(stats(TestUsers.ALICE, code, null).statusCode()).isEqualTo(200);
        data.markDeleted(code);
        HttpResponse<String> deletedForOwner = stats(TestUsers.ALICE, code, null);
        HttpResponse<String> deletedForAdmin = stats(TestUsers.ADMIN, code, null);

        for (HttpResponse<String> response : List.of(absent, foreign, deletedForOwner, deletedForAdmin)) {
            assertNotFound(response);
            assertThat(response.body()).isEqualTo(absent.body());
            assertThat(ApiClient.stableHeaders(response)).isEqualTo(ApiClient.stableHeaders(absent));
        }
    }

    @Test
    void shouldReturn401WithAChallengeForNoCredentialsAndForBadCredentialsAndBeforeAnyValidation() throws Exception {
        for (String query : List.of("", "timezone=PST", "tz=UTC")) {
            HttpResponse<String> anonymous = stats(null, CODE, query);
            assertThat(anonymous.statusCode()).isEqualTo(401);
            assertThat(api.json(anonymous).path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            assertThat(ApiClient.keys(api.json(anonymous))).isEqualTo(PROBLEM_KEYS);
            assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v ->
                    assertThat(v).startsWith("Basic"));
            assertThat(anonymous.body()).doesNotContain("totalClicks");
        }
        HttpResponse<String> wrong = send("GET", null, BASE + CODE + "/stats", "Authorization",
                ApiClient.rawBasicHeader("alice", "not-the-password"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(api.json(wrong).path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        HttpResponse<String> unknownUser = send("GET", null, BASE + CODE + "/stats", "Authorization",
                ApiClient.rawBasicHeader("mallory", "whatever"));
        assertThat(unknownUser.statusCode()).isEqualTo(401);
        assertThat(unknownUser.body()).isEqualTo(wrong.body());
    }

    @Test
    void shouldReturnStatsForADeactivatedLinkToItsOwnerAndAdminButNotToAnotherUser() throws Exception {
        data.seedClickEvents("AliceDea1", instants("2026-03-01T12:00:00Z"));

        for (String user : List.of(TestUsers.ALICE, TestUsers.ADMIN)) {
            JsonNode body = ok(stats(user, "AliceDea1", "from=2026-03-01&to=2026-03-01"));
            assertThat(counts(body)).containsExactly(1L);
            assertThat(body.path("totalClicks").asLong()).isEqualTo(1);
        }
        assertNotFound(stats(TestUsers.BOB, "AliceDea1", "from=2026-03-01&to=2026-03-01"));
    }

    @Test
    void shouldStopReturningStatsWhenTheLinkIsSoftDeletedEvenForItsOwnerAndAdmin() throws Exception {
        data.seedClickEvents("AliceAct1", instants("2026-03-01T12:00:00Z"));
        assertThat(stats(TestUsers.ALICE, "AliceAct1", null).statusCode()).isEqualTo(200);
        data.markDeleted("AliceAct1");

        assertNotFound(stats(TestUsers.ALICE, "AliceAct1", null));
        assertNotFound(stats(TestUsers.ADMIN, "AliceAct1", null));
    }

    // ---- the other methods, content negotiation, HEAD, caching ----

    @ParameterizedTest
    @CsvSource({
            "alice,AliceAct1,-,200,-", "bob,AliceAct1,-,404,SHORT_URL_NOT_FOUND",
            "alice,AliceDel1,-,404,SHORT_URL_NOT_FOUND", "alice,Nothing9,-,404,SHORT_URL_NOT_FOUND",
            "alice,AliceAct1,timezone=PST,400,VALIDATION_FAILED", "alice,AliceAct1,tz=UTC,400,MALFORMED_REQUEST",
            "bob,AliceAct1,from=2026-03-09&to=2026-03-07,400,VALIDATION_FAILED",
            "anon,AliceAct1,-,401,AUTHENTICATION_REQUIRED"})
    void shouldAnswerHeadWithTheSameStatusAndHeadersAsGetAndAnEmptyBody(String user, String code, String query,
            int status, String errorCode) throws Exception {
        String caller = "anon".equals(user) ? null : user;
        String q = "-".equals(query) ? null : query;
        // Control: the same path answers 200 to GET for the owner, so a HEAD refusal is about the request.
        assertThat(stats(TestUsers.ALICE, "AliceAct1", null).statusCode()).isEqualTo(200);

        HttpResponse<String> get = stats(caller, code, q);
        String path = BASE + code + "/stats" + (q == null ? "" : "?" + q);
        HttpResponse<String> head = send("HEAD", caller, path);

        assertThat(get.statusCode()).isEqualTo(status);
        if (!"-".equals(errorCode)) {
            assertThat(api.json(get).path("errorCode").asText()).isEqualTo(errorCode);
        }
        assertThat(head.statusCode()).isEqualTo(status);
        assertThat(head.body()).isEmpty();
        assertThat(get.body()).isNotEmpty();
        assertThat(ApiClient.headersExceptFraming(head)).isEqualTo(ApiClient.headersExceptFraming(get));
        assertThat(head.headers().firstValue("Content-Type")).isEqualTo(get.headers().firstValue("Content-Type"));
    }

    @Test
    void shouldNotCountAHeadOrGetOfTheStatsAsAClickAndLeaveTheRowUntouched() throws Exception {
        data.seedClickEvents("AliceAct1", instants("2026-03-01T12:00:00Z"));
        Map<String, Object> before = data.rowState("AliceAct1");
        int events = data.clickEventCount();

        assertThat(stats(TestUsers.ALICE, "AliceAct1", null).statusCode()).isEqualTo(200);
        assertThat(send("HEAD", TestUsers.ALICE, BASE + "AliceAct1/stats").statusCode()).isEqualTo(200);
        assertThat(stats(TestUsers.ADMIN, "AliceAct1", null).statusCode()).isEqualTo(200);

        assertThat(data.rowState("AliceAct1")).isEqualTo(before);
        assertThat(data.clickEventCount()).isEqualTo(events);
    }

    @Test
    void shouldPinTheDefaultNoStoreCacheControlOnSuccessAndOnErrors() throws Exception {
        for (HttpResponse<String> response : List.of(stats(TestUsers.ALICE, "AliceAct1", null),
                stats(TestUsers.BOB, "AliceAct1", null), alice("timezone=PST"), stats(null, "AliceAct1", null))) {
            assertThat(response.headers().allValues("Cache-Control")).containsExactly(NO_STORE);
        }
    }

    @Test
    void shouldAnswer406ToAnAcceptThatExcludesJsonBeforeAnyValidationOrLookup() throws Exception {
        for (String query : List.of("", "timezone=PST", "tz=UTC")) {
            String path = BASE + CODE + "/stats" + (query.isEmpty() ? "" : "?" + query);
            HttpResponse<String> xml = send("GET", TestUsers.ALICE, path, "Accept", "application/xml");
            assertThat(xml.statusCode()).isEqualTo(406);
            assertThat(api.json(xml).path("errorCode").asText()).isEqualTo("NOT_ACCEPTABLE");
        }
        HttpResponse<String> problemOnly = send("GET", TestUsers.ALICE, BASE + CODE + "/stats", "Accept",
                "application/problem+json");
        assertThat(problemOnly.statusCode()).isEqualTo(406);
        assertThat(send("GET", TestUsers.ALICE, BASE + CODE + "/stats", "Accept", "application/json").statusCode())
                .isEqualTo(200);
        assertThat(send("GET", TestUsers.ALICE, BASE + CODE + "/stats", "Accept", "*/*").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH"})
    void shouldAnswer405ForWritesOnTheStatsPathAndRecordTheAllowHeader(String method) throws Exception {
        HttpResponse<String> response = api.send(method, BASE + CODE + "/stats", TestUsers.ALICE,
                "application/json", "{}");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow ->
                assertThat(allow).contains("GET").doesNotContain("POST").doesNotContain("DELETE"));
        assertThat(data.rowState(CODE).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void shouldAnswerDeleteOnTheStatsPathWith403ForAUserAnd405ForAnAdminAndDeleteNothing() throws Exception {
        HttpResponse<String> user = send("DELETE", TestUsers.ALICE, BASE + CODE + "/stats");
        HttpResponse<String> admin = send("DELETE", TestUsers.ADMIN, BASE + CODE + "/stats");

        assertThat(user.statusCode()).isEqualTo(403);
        assertThat(api.json(user).path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(admin.statusCode()).isEqualTo(405);
        assertThat(api.json(admin).path("errorCode").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(data.lifecycleState(CODE).status()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldAnswerATrailingSlashWithAResourceNotFoundThatIsNotTheShortUrlNotFound() throws Exception {
        HttpResponse<String> response = send("GET", TestUsers.ALICE, BASE + CODE + "/stats/");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(send("GET", TestUsers.ALICE, BASE + CODE + "/stats").statusCode()).isEqualTo(200);
    }

    @Test
    void shouldSetTheInstanceOfAnErrorToThePathWithoutTheQuery() throws Exception {
        JsonNode body = api.json(stats(TestUsers.BOB, CODE, "timezone=UTC&from=2026-03-01"));

        assertThat(body.path("instance").asText()).isEqualTo(BASE + CODE + "/stats");
    }

    // ---- D101, D94: where the numbers come from ----

    @Test
    void shouldTakeTotalClicksFromTheStoredCounterWhileTheRangeCountsEvents() throws Exception {
        data.seedClicks(CODE, 41, Instant.parse("2026-01-01T00:00:00Z"));

        JsonNode body = ok(alice("from=2026-03-01&to=2026-03-03"));

        assertThat(body.path("totalClicks").asLong()).isEqualTo(41);
        assertThat(body.path("clicksInRange").asLong()).isZero();
        assertThat(counts(body)).containsExactly(0L, 0L, 0L);
        assertThat(lastAccess(body)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(data.clickEventCount(CODE)).isZero();
    }

    @Test
    void shouldAddSeededEventsOnTopOfTheStoredCounterWithoutRecomputingIt() throws Exception {
        data.seedClicks(CODE, 41, Instant.parse("2026-01-01T00:00:00Z"));
        data.seedClickEvents(CODE, instants("2026-03-01T12:00:00Z", "2026-03-02T12:00:00Z"));

        JsonNode body = ok(alice("from=2026-03-01&to=2026-03-02"));

        assertThat(body.path("totalClicks").asLong()).isEqualTo(43);
        assertThat(body.path("clicksInRange").asLong()).isEqualTo(2);
        assertThat(lastAccess(body)).isEqualTo(Instant.parse("2026-03-02T12:00:00Z"));
    }

    @Test
    void shouldShowAnExplicitNullLastAccessBeforeTheFirstClick() throws Exception {
        JsonNode body = ok(alice(null));

        assertThat(body.has("lastAccessedAt")).isTrue();
        assertThat(body.get("lastAccessedAt").isNull()).isTrue();
        assertThat(body.path("totalClicks").asLong(-1)).isZero();
        assertThat(body.path("clicksInRange").asLong(-1)).isZero();
    }

    @Test
    void shouldShowRealRedirectClicksAndTheLatestClickTimeAsTheLastAccess() throws Exception {
        clock.setInstant(Instant.parse("2026-03-08T04:59:59.999999999Z"));
        assertThat(api.send("GET", "/" + CODE, null, null, null).statusCode()).isEqualTo(302);
        clock.advance(Duration.ofSeconds(1));
        assertThat(api.send("GET", "/" + CODE, null, null, null).statusCode()).isEqualTo(302);
        clock.advance(Duration.ofHours(3));
        assertThat(api.send("GET", "/" + CODE, null, null, null).statusCode()).isEqualTo(302);

        JsonNode body = ok(alice("timezone=America/New_York&from=2026-03-07&to=2026-03-08"));

        assertThat(counts(body)).containsExactly(1L, 2L);
        assertThat(body.path("totalClicks").asLong()).isEqualTo(3);
        List<Instant> stored = data.clickedAts(CODE);
        assertThat(stored).hasSize(3);
        assertThat(Instant.parse(body.path("lastAccessedAt").asText())).isEqualTo(stored.get(2));
        assertThat(stored.get(2)).isEqualTo(Instant.parse("2026-03-08T08:00:00.999999Z"));
    }

    @Test
    void shouldKeepTheLaterLastAccessAndCountBothClicksWhenTheClockMovesBackwards() throws Exception {
        Instant later = Instant.parse("2026-03-08T12:00:00Z");
        Instant earlier = Instant.parse("2026-03-08T11:00:00Z");
        clock.setInstant(later);
        assertThat(api.send("GET", "/" + CODE, null, null, null).statusCode()).isEqualTo(302);
        clock.setInstant(earlier);
        assertThat(api.send("GET", "/" + CODE, null, null, null).statusCode()).isEqualTo(302);

        JsonNode body = ok(alice("from=2026-03-08&to=2026-03-08"));
        JsonNode details = api.json(send("GET", TestUsers.ALICE, BASE + CODE));

        // Non-vacuity: both clicks were written (two events, a count of two), the second at the earlier instant.
        assertThat(data.clickedAts(CODE)).containsExactly(earlier, later);
        assertThat(data.lifecycleState(CODE).clickCount()).isEqualTo(2);
        assertThat(body.path("totalClicks").asLong()).isEqualTo(2);
        assertThat(counts(body)).containsExactly(2L);
        assertThat(Instant.parse(body.path("lastAccessedAt").asText())).isEqualTo(later);
        assertThat(Instant.parse(details.path("lastAccessedAt").asText())).isEqualTo(later);
        assertThat(details.path("clickCount").asLong()).isEqualTo(2);
    }

    // ---- logs (D56) ----

    @Test
    void shouldNeverWriteTheRejectedValuesToTheLog(CapturedOutput output) throws Exception {
        assertValidation(alice("timezone=Marker%2FZone9x&from=Marker-from-9x&to=Marker-to-9x"), "from", "timezone",
                "to");
        assertMalformed(alice("markerparam9x=1"));

        assertThat(output.getAll()).doesNotContain("Zone9x").doesNotContain("from-9x").doesNotContain("to-9x")
                .doesNotContain("markerparam9x");
    }

    // ---- D102: one snapshot for totalClicks, lastAccessedAt and daily ----

    private static final String WINDOW_DAY = "2026-03-01";
    private static final Instant COMMITTED_DURING_THE_REQUEST = Instant.parse("2026-03-01T12:00:00Z");

    /** Polls pg_stat_activity until another backend's statement matching {@code pattern} waits on a lock. */
    private void awaitBlocked(String pattern) {
        Awaitility.await("statement blocked on a lock: " + pattern).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> jdbc.queryForList("SELECT query FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query ILIKE ?",
                        String.class, pattern), waiting -> !waiting.isEmpty());
    }

    /** Commits one click (event plus counter), as the recorder would, on a connection that holds the table lock. */
    private void commitClick(Connection holder) throws SQLException {
        try (PreparedStatement insert = holder.prepareStatement(
                "INSERT INTO click_event (short_url_id, clicked_at) VALUES (?, ?)")) {
            insert.setLong(1, data.shortUrlId(CODE));
            insert.setObject(2, OffsetDateTime.ofInstant(COMMITTED_DURING_THE_REQUEST, ZoneOffset.UTC));
            insert.executeUpdate();
        }
        try (PreparedStatement update = holder.prepareStatement(
                "UPDATE short_url SET click_count = click_count + 1, last_accessed_at = ? WHERE short_code = ?")) {
            update.setObject(1, OffsetDateTime.ofInstant(COMMITTED_DURING_THE_REQUEST, ZoneOffset.UTC));
            update.setString(2, CODE);
            update.executeUpdate();
        }
        holder.commit();
    }

    /**
     * The two statements of a stats call, run by hand at the given isolation level: the counter first, then the
     * event count, which is forced to wait on a table lock while a click commits in between. Returns
     * {counter read first, event count read second}. This is the harness control: it shows that the wait does make
     * a read-committed reader see the click in the second statement only, so a snapshot result is not vacuous.
     */
    private long[] readCounterThenEventsWhileAClickCommits(int isolation) throws Exception {
        try (Connection reader = dataSource.getConnection(); Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(isolation);
            try (Statement lock = holder.createStatement()) {
                lock.execute("LOCK TABLE click_event IN ACCESS EXCLUSIVE MODE");
            }
            long counter;
            try (PreparedStatement first = reader.prepareStatement(
                    "SELECT click_count FROM short_url WHERE short_code = ?")) {
                first.setString(1, CODE);
                try (ResultSet rs = first.executeQuery()) {
                    rs.next();
                    counter = rs.getLong(1);
                }
            }
            Future<Long> events = pool.submit(() -> {
                try (PreparedStatement second = reader.prepareStatement(
                        "SELECT count(*) AS control_events FROM click_event WHERE short_url_id = ?")) {
                    second.setLong(1, data.shortUrlId(CODE));
                    try (ResultSet rs = second.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
            awaitBlocked("%control_events%");
            commitClick(holder);
            long counted = events.get(30, TimeUnit.SECONDS);
            reader.commit();
            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return new long[] {counter, counted};
        }
    }

    @Test
    void shouldShowInTheHarnessThatReadCommittedWouldSeeTheClickInTheSecondStatementOnly() throws Exception {
        long[] readCommitted = readCounterThenEventsWhileAClickCommits(Connection.TRANSACTION_READ_COMMITTED);

        assertThat(readCommitted).as("counter then events under READ COMMITTED").containsExactly(0, 1);
    }

    @Test
    void shouldShowInTheHarnessThatRepeatableReadHidesTheClickFromTheSecondStatement() throws Exception {
        long[] repeatableRead = readCounterThenEventsWhileAClickCommits(Connection.TRANSACTION_REPEATABLE_READ);

        assertThat(repeatableRead).as("counter then events under REPEATABLE READ").containsExactly(0, 0);
    }

    @Test
    void shouldServeOneSnapshotWhenAClickCommitsBetweenTheLinkLookupAndTheDailyQuery() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("LOCK TABLE click_event IN ACCESS EXCLUSIVE MODE");
            }
            Future<HttpResponse<String>> inFlight = pool.submit(
                    () -> alice("from=" + WINDOW_DAY + "&to=" + WINDOW_DAY));
            // The link lookup has run (it reads short_url only); the daily query now waits for the table lock.
            awaitBlocked("%width_bucket%");
            commitClick(holder);

            JsonNode during = ok(inFlight.get(30, TimeUnit.SECONDS));

            // One snapshot: neither the counter nor the events nor the last access show the click (D102).
            assertThat(during.path("totalClicks").asLong()).isZero();
            assertThat(during.path("clicksInRange").asLong()).isZero();
            assertThat(counts(during)).containsExactly(0L);
            assertThat(during.get("lastAccessedAt").isNull()).isTrue();
        }
        // Non-vacuity: the click was really committed, and the next call shows it everywhere.
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
        JsonNode after = ok(alice("from=" + WINDOW_DAY + "&to=" + WINDOW_DAY));
        assertThat(after.path("totalClicks").asLong()).isEqualTo(1);
        assertThat(after.path("clicksInRange").asLong()).isEqualTo(1);
        assertThat(counts(after)).containsExactly(1L);
        assertThat(Instant.parse(after.path("lastAccessedAt").asText())).isEqualTo(COMMITTED_DURING_THE_REQUEST);
    }

    @Test
    void shouldLeaveEveryPooledConnectionAtReadCommittedAndWritableAfterStatsCalls() throws Exception {
        for (int i = 0; i < 12; i++) {
            ok(alice(null));
        }
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                held.add(dataSource.getConnection());
            }
            for (Connection connection : held) {
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                assertThat(connection.isReadOnly()).isFalse();
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
        // The next write still works on the same pool: a click is counted after the stats calls.
        assertThat(api.send("GET", "/" + CODE, null, null, null).statusCode()).isEqualTo(302);
        assertThat(data.lifecycleState(CODE).clickCount()).isEqualTo(1);
    }
}
