package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-007 acceptance criteria through a real Tomcat and Testcontainers PostgreSQL: ownership (D4), deleted
 * links (D13), malformed codes (D72), the identical 404 (D74), content negotiation (D70), caching (D73),
 * the create/GET round trip (D58) and the check-after-409 use (D71). Every 404 assertion also asserts the
 * errorCode, so an unmapped path (RESOURCE_NOT_FOUND) can never satisfy a test that wants
 * SHORT_URL_NOT_FOUND. Credentials come from {@link TestUsers} and are never printed.
 */
class GetShortUrlIT extends IntegrationTestBase {

    private static final String BASE = "/api/v1/urls/";
    private static final String NO_STORE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final Instant SEEDED_LAST_ACCESS = Instant.parse("2026-03-01T10:15:30Z");
    private static final Set<String> RESOURCE_KEYS = Set.of("shortCode", "shortUrl", "originalUrl",
            "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt");
    private static final Set<String> PROBLEM_KEYS = Set.of("type", "title", "status", "detail",
            "instance", "errorCode");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void seedMatrix() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        data.seed("AliceAct1", "ACTIVE", "https://example.com/alice-active", TestUsers.ALICE);
        data.seedClicks("AliceAct1", 5, SEEDED_LAST_ACCESS);
        data.seed("AliceDea1", "DEACTIVATED", "https://example.com/alice-deactivated", TestUsers.ALICE);
        data.seed("AliceDel1", "DELETED", "https://example.com/alice-deleted", TestUsers.ALICE);
        data.seed("BobAct1", "ACTIVE", "https://example.com/bob-active", TestUsers.BOB);
        data.seed("AdminAct1", "ACTIVE", "https://example.com/admin-active", TestUsers.ADMIN);
    }

    private HttpResponse<String> get(String user, String code, String... headers) throws Exception {
        return api.send("GET", BASE + code, user, null, null, headers);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return api.json(response);
    }

    private void assertNotFound(HttpResponse<String> response, String path) throws Exception {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(body.path("errorCode").asText()).isEqualTo("SHORT_URL_NOT_FOUND");
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
        assertThat(body.path("instance").asText()).isEqualTo(path(path));
    }

    private static String path(String suffix) {
        return suffix.startsWith("/") ? suffix : BASE + suffix;
    }

    private int rowCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM short_url", Integer.class);
        return n == null ? 0 : n;
    }

    /** Headers except Date (unlike ApiClient.stableHeaders, Content-Length is kept), keyed case-insensitively. */
    private static Map<String, List<String>> headersExceptDate(HttpResponse<String> response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((name, values) -> {
            if (!"date".equalsIgnoreCase(name)) {
                headers.put(name, values);
            }
        });
        return headers;
    }

    // ---- AC1 to AC5: ownership matrix ----

    @ParameterizedTest
    @CsvSource({
            "alice,AliceAct1,200", "alice,AliceDea1,200", "alice,AliceDel1,404", "alice,BobAct1,404",
            "alice,AdminAct1,404",
            "bob,AliceAct1,404", "bob,AliceDea1,404", "bob,AliceDel1,404", "bob,BobAct1,200", "bob,AdminAct1,404",
            "admin,AliceAct1,200", "admin,AliceDea1,200", "admin,AliceDel1,404", "admin,BobAct1,200",
            "admin,AdminAct1,200"})
    void shouldApplyOwnershipAndDeletionRulesForEveryCallerAndRow(String user, String code, int expected)
            throws Exception {
        HttpResponse<String> response = get(user, code);

        assertThat(response.statusCode()).as("%s reading %s", user, code).isEqualTo(expected);
        if (expected == 404) {
            assertNotFound(response, code);
        } else {
            assertThat(ApiClient.contentType(response)).startsWith("application/json");
            JsonNode body = json(response);
            assertThat(ApiClient.keys(body)).isEqualTo(RESOURCE_KEYS);
            assertThat(body.path("shortCode").asText()).isEqualTo(code);
            assertThat(body.path("shortUrl").asText()).isEqualTo("https://short.example/" + code);
            assertThat(body.path("status").asText()).isEqualTo(data.rowState(code).get("status"));
        }
    }

    @Test
    void shouldReturnValuesEqualToTheDatabaseRow() throws Exception {
        JsonNode body = json(get(TestUsers.ALICE, "AliceAct1"));

        assertThat(body.path("originalUrl").asText()).isEqualTo("https://example.com/alice-active");
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("customAlias").isBoolean()).isTrue();
        assertThat(body.path("customAlias").asBoolean()).isFalse();
        assertThat(body.path("clickCount").asLong(-1)).isEqualTo(5);
        assertThat(Instant.parse(body.path("lastAccessedAt").asText())).isEqualTo(SEEDED_LAST_ACCESS);
        assertThat(Instant.parse(body.path("createdAt").asText())).isEqualTo(data.createdAt("AliceAct1"));
        for (String hidden : List.of("createdBy", "id", "updatedAt", "version")) {
            assertThat(body.has(hidden)).as(hidden).isFalse();
        }
    }

    @Test
    void shouldReturnLastAccessedAtAsExplicitNullBeforeTheFirstClick() throws Exception {
        JsonNode body = json(get(TestUsers.ALICE, "AliceDea1"));

        assertThat(body.has("lastAccessedAt")).isTrue();
        assertThat(body.get("lastAccessedAt").isNull()).isTrue();
        assertThat(body.path("clickCount").asLong(-1)).isZero();
        assertThat(body.path("status").asText()).isEqualTo("DEACTIVATED");
    }

    // ---- AC3, AC4, AC5: the four 404 causes are indistinguishable (D72, D74) ----

    @Test
    void shouldReturnByteIdentical404ForAbsentForeignAndDeletedCodeOnTheSamePath() throws Exception {
        String code = "Same1234";
        HttpResponse<String> absent = get(TestUsers.BOB, code);
        data.seed(code, "ACTIVE", "https://example.com/same", TestUsers.ALICE);
        HttpResponse<String> foreign = get(TestUsers.BOB, code);
        // Positive control: the owner does see the row, so the 404 above was about ownership, not absence.
        assertThat(get(TestUsers.ALICE, code).statusCode()).isEqualTo(200);
        data.markDeleted(code);
        HttpResponse<String> deletedForOwner = get(TestUsers.ALICE, code);
        HttpResponse<String> deletedForAdmin = get(TestUsers.ADMIN, code);

        for (HttpResponse<String> response : List.of(absent, foreign, deletedForOwner, deletedForAdmin)) {
            assertNotFound(response, code);
            assertThat(response.body()).isEqualTo(absent.body());
            assertThat(headersExceptDate(response)).isEqualTo(headersExceptDate(absent));
            assertThat(response.headers().firstValue("Allow")).isEmpty();
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "a-b", "a_b", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ab%20c", "abc%C3%A9", "abc.json",
            "%41%42"})
    void shouldReturnTheSame404BodyExceptInstanceForMalformedCodes(String code) throws Exception {
        HttpResponse<String> reference = get(TestUsers.ALICE, "Nothing1");
        HttpResponse<String> response = get(TestUsers.ALICE, code);

        assertNotFound(response, code);
        ObjectNode expected = (ObjectNode) json(reference).deepCopy();
        ObjectNode actual = (ObjectNode) json(response).deepCopy();
        expected.remove("instance");
        actual.remove("instance");
        assertThat(actual).isEqualTo(expected);
        assertThat(headersExceptDate(response).keySet()).isEqualTo(headersExceptDate(reference).keySet());
        assertThat(response.headers().firstValue("Content-Type"))
                .isEqualTo(reference.headers().firstValue("Content-Type"));
    }

    @Test
    void shouldNotTreatAReservedWordAsMalformedWhenItExists() throws Exception {
        // D48: the reserved-word list applies to creation only; an existing code stays visible to its owner.
        data.seed("Health", "ACTIVE", "https://example.com/health-page", TestUsers.ALICE);

        HttpResponse<String> response = get(TestUsers.ALICE, "Health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("originalUrl").asText()).isEqualTo("https://example.com/health-page");
    }

    // ---- D6: case sensitivity ----

    @Test
    void shouldTreatCodesDifferingOnlyInCaseAsDifferentLinksWithDifferentOwners() throws Exception {
        data.seed("Mixed1", "ACTIVE", "https://example.com/alice-mixed", TestUsers.ALICE);
        data.seed("mixed1", "ACTIVE", "https://example.com/bob-mixed", TestUsers.BOB);

        HttpResponse<String> aliceUpper = get(TestUsers.ALICE, "Mixed1");
        assertThat(aliceUpper.statusCode()).isEqualTo(200);
        assertThat(json(aliceUpper).path("originalUrl").asText()).isEqualTo("https://example.com/alice-mixed");
        assertNotFound(get(TestUsers.ALICE, "mixed1"), "mixed1");
        assertNotFound(get(TestUsers.ALICE, "MIXED1"), "MIXED1");

        HttpResponse<String> bobLower = get(TestUsers.BOB, "mixed1");
        assertThat(bobLower.statusCode()).isEqualTo(200);
        assertThat(json(bobLower).path("originalUrl").asText()).isEqualTo("https://example.com/bob-mixed");
        assertNotFound(get(TestUsers.BOB, "Mixed1"), "Mixed1");
    }

    // ---- D58: round trip ----

    @Test
    void shouldReturnTheCreateResponseVerbatimWhenGettingAGeneratedCode() throws Exception {
        HttpResponse<String> create =
                api.post(TestUsers.ALICE, api.createBody("https://example.com/round-trip", null));
        assertThat(create.statusCode()).isEqualTo(201);
        String location = create.headers().firstValue("Location").orElseThrow();
        String path = location.substring(location.indexOf("/api/"));

        HttpResponse<String> owner = api.send("GET", path, TestUsers.ALICE, null, null);
        HttpResponse<String> admin = api.send("GET", path, TestUsers.ADMIN, null, null);

        assertThat(owner.statusCode()).isEqualTo(200);
        assertThat(json(owner)).isEqualTo(json(create));
        assertThat(ApiClient.keys(json(owner))).isEqualTo(RESOURCE_KEYS);
        assertThat(json(owner).path("clickCount").asLong(-1)).isZero();
        assertThat(json(owner).get("lastAccessedAt").isNull()).isTrue();
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(json(admin)).isEqualTo(json(create));
    }

    @Test
    void shouldReturnTheCreateResponseVerbatimWhenGettingACustomAlias() throws Exception {
        HttpResponse<String> create =
                api.post(TestUsers.BOB, api.createBody("https://example.com/branded", "Branded1"));
        assertThat(create.statusCode()).isEqualTo(201);

        HttpResponse<String> owner = get(TestUsers.BOB, "Branded1");

        assertThat(owner.statusCode()).isEqualTo(200);
        assertThat(json(owner)).isEqualTo(json(create));
        assertThat(json(owner).path("customAlias").asBoolean()).isTrue();
    }

    // ---- D71: check after 409 ----

    @Test
    void shouldLetTheOwnerConfirmALostCreateAfterA409AndTellAnotherUserItIsNotTheirs() throws Exception {
        String firstUrl = "https://example.com/lost-201-first";
        assertThat(api.post(TestUsers.ALICE, api.createBody(firstUrl, "Lost201x")).statusCode()).isEqualTo(201);
        HttpResponse<String> retry =
                api.post(TestUsers.ALICE, api.createBody("https://example.com/lost-201-retry", "Lost201x"));
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(json(retry).path("errorCode").asText()).isEqualTo("ALIAS_ALREADY_EXISTS");

        HttpResponse<String> check = get(TestUsers.ALICE, "Lost201x");

        assertThat(check.statusCode()).isEqualTo(200);
        assertThat(json(check).path("originalUrl").asText()).isEqualTo(firstUrl);

        HttpResponse<String> bobCreate =
                api.post(TestUsers.BOB, api.createBody("https://example.com/bob-tries", "Lost201x"));
        assertThat(bobCreate.statusCode()).isEqualTo(409);
        assertThat(json(bobCreate).path("errorCode").asText()).isEqualTo("ALIAS_ALREADY_EXISTS");
        assertNotFound(get(TestUsers.BOB, "Lost201x"), "Lost201x");
    }

    // ---- D70: content negotiation ----

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406WithProblemBodyAndChangeNothingWhenAcceptIsNotJson(String accept) throws Exception {
        Map<String, Object> before = data.rowState("AliceAct1");
        int rows = rowCount();

        HttpResponse<String> response = get(TestUsers.ALICE, "AliceAct1", "Accept", accept);

        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        assertThat(json(response).path("errorCode").asText()).isEqualTo("NOT_ACCEPTABLE");
        assertThat(data.rowState("AliceAct1")).isEqualTo(before);
        assertThat(rowCount()).isEqualTo(rows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "*/*", "application/json, application/xml;q=0.5"})
    void shouldStillReturn200ForAnAcceptableAcceptHeader(String accept) throws Exception {
        HttpResponse<String> response = get(TestUsers.ALICE, "AliceAct1", "Accept", accept);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
    }

    @Test
    void shouldReturn406WithEmptyBodyForAnUnparseableAcceptHeader() throws Exception {
        HttpResponse<String> response = get(TestUsers.ALICE, "AliceAct1", "Accept", "foo");

        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void shouldReturn406NotAnswerAboutTheCodeWhenAcceptIsNotJsonForAnUnknownCode() throws Exception {
        HttpResponse<String> response = get(TestUsers.ALICE, "Nothing1", "Accept", "application/xml");

        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(json(response).path("errorCode").asText()).isEqualTo("NOT_ACCEPTABLE");
    }

    // ---- HEAD (recorded behaviour) ----

    @Test
    void shouldAnswerHeadLikeGetWithoutABody() throws Exception {
        HttpResponse<String> get = get(TestUsers.ALICE, "AliceAct1");
        HttpResponse<String> head = api.send("HEAD", BASE + "AliceAct1", TestUsers.ALICE, null, null);

        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(ApiClient.contentType(head)).startsWith("application/json");
        // Recorded behaviour (embedded Tomcat, Boot 3.5.16, today): HEAD returns 200 with Content-Type
        // application/json, no body and no Content-Length. No requirement depends on that, so the pin is: if a
        // Content-Length is present, it equals the byte length of the GET body for the same code.
        int getLength = get.body().getBytes(StandardCharsets.UTF_8).length;
        assertThat(getLength).isPositive();
        head.headers().firstValue("Content-Length")
                .ifPresent(length -> assertThat(Long.parseLong(length)).isEqualTo(getLength));
    }

    @Test
    void shouldAnswerHeadWith404ForAnotherUsersLinkAnd401ForAnonymousWithoutABody() throws Exception {
        HttpResponse<String> foreign = api.send("HEAD", BASE + "AliceAct1", TestUsers.BOB, null, null);
        HttpResponse<String> anonymous = api.send("HEAD", BASE + "AliceAct1", null, null, null);
        HttpResponse<String> deletedForAdmin = api.send("HEAD", BASE + "AliceDel1", TestUsers.ADMIN, null, null);
        HttpResponse<String> adminOk = api.send("HEAD", BASE + "AliceAct1", TestUsers.ADMIN, null, null);
        // Same-path control for the deleted case: the owner's HEAD is served, then the row is deleted.
        data.seed("HeadDel1", "ACTIVE", "https://example.com/head-deleted", TestUsers.ALICE);
        HttpResponse<String> beforeDelete = api.send("HEAD", BASE + "HeadDel1", TestUsers.ALICE, null, null);
        data.markDeleted("HeadDel1");
        HttpResponse<String> deletedSamePath = api.send("HEAD", BASE + "HeadDel1", TestUsers.ADMIN, null, null);

        // A HEAD 404 has no body, so errorCode cannot be asserted. A 200 on the same path in this test
        // (adminOk for the foreign case, beforeDelete for the deleted case) proves the mapping was reached,
        // so the 404 is not the unmapped RESOURCE_NOT_FOUND.
        assertThat(adminOk.statusCode()).isEqualTo(200);
        assertThat(beforeDelete.statusCode()).isEqualTo(200);
        assertThat(deletedSamePath.statusCode()).isEqualTo(404);
        assertThat(deletedSamePath.body()).isEmpty();
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).isEmpty();
        assertThat(deletedForAdmin.statusCode()).isEqualTo(404);
        assertThat(deletedForAdmin.body()).isEmpty();
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).isEmpty();
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).isPresent();
    }

    // ---- A read writes nothing ----

    @Test
    void shouldNotWriteAnythingWhenReadingByGetOrHeadAsOwnerOrAdmin() throws Exception {
        for (String code : List.of("AliceAct1", "AliceDea1")) {
            Map<String, Object> before = data.rowState(code);
            for (String method : List.of("GET", "HEAD")) {
                for (String user : List.of(TestUsers.ALICE, TestUsers.ADMIN)) {
                    // Non-vacuity: each request is served (200), so the handler really ran.
                    assertThat(api.send(method, BASE + code, user, null, null).statusCode())
                            .as("%s %s as %s", method, code, user).isEqualTo(200);
                }
            }
            assertThat(data.rowState(code)).as(code).isEqualTo(before);
        }
    }

    @Test
    void shouldObserveLiveDatabaseChangesBetweenReadsSoTheNoWriteCheckIsNotVacuous() throws Exception {
        assertThat(json(get(TestUsers.ALICE, "AliceAct1")).path("clickCount").asLong()).isEqualTo(5);

        data.seedClicks("AliceAct1", 9, SEEDED_LAST_ACCESS.plusSeconds(60));

        JsonNode after = json(get(TestUsers.ALICE, "AliceAct1"));
        assertThat(after.path("clickCount").asLong()).isEqualTo(9);
        assertThat(Instant.parse(after.path("lastAccessedAt").asText()))
                .isEqualTo(SEEDED_LAST_ACCESS.plusSeconds(60));
    }

    // ---- D73: caching ----

    @Test
    void shouldForbidCachingOnTheSuccessAndTheNotFoundResponse() throws Exception {
        List<HttpResponse<String>> responses =
                List.of(get(TestUsers.ALICE, "AliceAct1"), get(TestUsers.BOB, "AliceAct1"));
        for (HttpResponse<String> response : responses) {
            assertThat(response.headers().allValues("Cache-Control")).containsExactly(NO_STORE);
            assertThat(response.headers().allValues("Pragma")).containsExactly("no-cache");
            assertThat(response.headers().allValues("Expires")).containsExactly("0");
        }
    }

    // ---- Path variants ----

    @Test
    void shouldReturnResourceNotFoundNotShortUrlNotFoundForATrailingSlashOrExtraSegment() throws Exception {
        for (String path : List.of(BASE + "AliceAct1/", BASE + "AliceAct1/x")) {
            HttpResponse<String> response = api.send("GET", path, TestUsers.ALICE, null, null);

            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(json(response).path("errorCode").asText()).as(path).isEqualTo("RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void shouldReturn403AccessDeniedForAnUpperCasedApiPath() throws Exception {
        HttpResponse<String> response = api.send("GET", "/API/v1/urls/AliceAct1", TestUsers.ALICE, null, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json(response).path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    // PATCH is mapped on this path since US-009, so it is no longer a 405 case; LifecycleIT covers it.
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST"})
    void shouldReturn405WithAllowGetForOtherMethodsOnACodeAndChangeNothing(String method) throws Exception {
        Map<String, Object> before = data.rowState("AliceAct1");
        int rows = rowCount();

        HttpResponse<String> response = api.send(method, BASE + "AliceAct1", TestUsers.ALICE, "application/json", "{}");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(json(response).path("errorCode").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.headers().firstValue("Allow").orElse("")).contains("GET");
        assertThat(data.rowState("AliceAct1")).isEqualTo(before);
        assertThat(rowCount()).isEqualTo(rows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AliceAct1;x=1", "%2e%2e", "%0aAliceAct1"})
    void shouldRejectFirewallForbiddenPathsBeforeTheHandler(String code) throws Exception {
        HttpResponse<String> response = get(TestUsers.ALICE, code);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    // ---- AC6: authentication ----

    @Test
    void shouldReturn401WithBaseProblemKeysForAnonymousAndForBadCredentials() throws Exception {
        String badToken = Base64.getEncoder()
                .encodeToString((TestUsers.ALICE + ":wrong-password").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> anonymous = get(null, "AliceAct1");
        HttpResponse<String> wrong = get(null, "AliceAct1", "Authorization", "Basic " + badToken);
        HttpResponse<String> anonymousUnknownCode = get(null, "Nothing1");

        for (HttpResponse<String> response : List.of(anonymous, wrong)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
            JsonNode body = json(response);
            assertThat(body.path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
            assertThat(body.path("instance").asText()).isEqualTo(BASE + "AliceAct1");
            assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
            assertThat(response.body()).doesNotContain("alice-active");
        }
        // Authentication comes before the lookup: an unknown code gets the same 401, not a 404.
        assertThat(anonymousUnknownCode.statusCode()).isEqualTo(401);
    }
}
