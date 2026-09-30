package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-009 acceptance criteria through a real Tomcat and Testcontainers PostgreSQL: PATCH ownership (D4, D13),
 * the D26 conflicts, ADMIN-only soft delete (D1, D3, D36, D46, D51), what a deleted code still does (D1),
 * content negotiation and media types (D70, D88), strict booleans (D89), untouched click data (D27, D90),
 * path variants, authentication (AC12) and logging. Concurrency lives in {@link LifecycleConcurrencyIT}.
 *
 * <p>Every "nothing changed" assertion is paired with a positive control on the same row that does change
 * it (the version bumps), and every 404 asserts its errorCode so an unmapped path (RESOURCE_NOT_FOUND) can
 * never satisfy a test that wants SHORT_URL_NOT_FOUND. Rows are created through the API where it can, and
 * truncated before each test only. Credentials come from {@link TestUsers} and are never printed.
 */
@ExtendWith(OutputCaptureExtension.class)
class LifecycleIT extends IntegrationTestBase {

    private static final String BASE = "/api/v1/urls/";
    private static final String JSON = "application/json";
    private static final String NOT_FOUND = "SHORT_URL_NOT_FOUND";
    private static final String ACTIVE_BODY = "{\"active\": true}";
    private static final String INACTIVE_BODY = "{\"active\": false}";
    private static final Set<String> PROBLEM_KEYS =
            Set.of("type", "title", "status", "detail", "instance", "errorCode");
    private static final Set<String> RESOURCE_KEYS = Set.of("shortCode", "shortUrl", "originalUrl",
            "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt");
    private static final Instant SEEDED_LAST_ACCESS = Instant.parse("2026-03-01T10:15:30Z");

    private static final String ALICE_ACTIVE = "AliceAct1";
    private static final String ALICE_DEACTIVATED = "AliceDea1";
    private static final String ALICE_DELETED = "AliceDel1";
    private static final String BOB_ACTIVE = "BobAct1";
    private static final String ADMIN_ACTIVE = "AdminAct1";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void seedMatrix() throws Exception {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        create(TestUsers.ALICE, ALICE_ACTIVE);
        create(TestUsers.ALICE, ALICE_DEACTIVATED);
        assertThat(patch(TestUsers.ALICE, ALICE_DEACTIVATED, INACTIVE_BODY).statusCode()).isEqualTo(200);
        create(TestUsers.ALICE, ALICE_DELETED);
        assertThat(delete(TestUsers.ADMIN, ALICE_DELETED).statusCode()).isEqualTo(204);
        create(TestUsers.BOB, BOB_ACTIVE);
        create(TestUsers.ADMIN, ADMIN_ACTIVE);
    }

    // ---- helpers ----

    private void create(String owner, String code) throws Exception {
        HttpResponse<String> created = api.post(owner,
                api.createBody("https://example.com/lifecycle/" + code, code));
        assertThat(created.statusCode()).as("create %s as %s", code, owner).isEqualTo(201);
    }

    private HttpResponse<String> patch(String user, String code, String body, String... headers) throws Exception {
        return api.send("PATCH", BASE + code, user, JSON, body, headers);
    }

    private HttpResponse<String> delete(String user, String code, String... headers) throws Exception {
        return api.send("DELETE", BASE + code, user, null, null, headers);
    }

    private HttpResponse<String> get(String user, String code) throws Exception {
        return api.send("GET", BASE + code, user, null, null);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return api.json(response);
    }

    private static String bodyFor(boolean active) {
        return active ? ACTIVE_BODY : INACTIVE_BODY;
    }

    /** A problem+json response with exactly the base keys, the given status and errorCode, and this path. */
    private void assertProblem(HttpResponse<String> response, int status, String errorCode, String path)
            throws Exception {
        assertThat(response.statusCode()).as("status of %s", path).isEqualTo(status);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
        assertThat(body.path("errorCode").asText()).as("errorCode of %s", path).isEqualTo(errorCode);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("instance").asText()).isEqualTo(path);
    }

    private void assertNotFound(HttpResponse<String> response, String path) throws Exception {
        assertProblem(response, 404, NOT_FOUND, path);
    }

    /** The problem body without its instance, for comparing bodies that differ only in the path. */
    private JsonNode withoutInstance(HttpResponse<String> response) throws Exception {
        ObjectNode copy = ((ObjectNode) json(response)).deepCopy();
        copy.remove("instance");
        return copy;
    }

    /** Applies the opposite valid change as the row's owner: proves the row still accepts a write. */
    private void assertRowStillAcceptsAWrite(String owner, String code) throws Exception {
        LifecycleState before = data.lifecycleState(code);
        boolean wasActive = "ACTIVE".equals(before.status());
        HttpResponse<String> control = patch(owner, code, bodyFor(!wasActive));
        assertThat(control.statusCode()).as("positive control on %s", code).isEqualTo(200);
        assertThat(data.lifecycleState(code).version()).isEqualTo(before.version() + 1);
    }

    // ---- AC1 to AC4, AC7 to AC10: the PATCH matrix ----

    /** Expected PATCH outcome from the requirements: D4, D13, D26 (visibility first, then the transition). */
    private static int expectedStatus(String user, String code, boolean active) {
        if (ALICE_DELETED.equals(code)) {
            return 404;
        }
        String owner = switch (code) {
            case ALICE_ACTIVE, ALICE_DEACTIVATED -> TestUsers.ALICE;
            case BOB_ACTIVE -> TestUsers.BOB;
            case ADMIN_ACTIVE -> TestUsers.ADMIN;
            default -> throw new IllegalArgumentException(code);
        };
        if (!TestUsers.ADMIN.equals(user) && !owner.equals(user)) {
            return 404;
        }
        boolean rowActive = !ALICE_DEACTIVATED.equals(code);
        return rowActive == active ? 409 : 200;
    }

    private static String ownerOf(String code) {
        return switch (code) {
            case ALICE_ACTIVE, ALICE_DEACTIVATED, ALICE_DELETED -> TestUsers.ALICE;
            case BOB_ACTIVE -> TestUsers.BOB;
            case ADMIN_ACTIVE -> TestUsers.ADMIN;
            default -> throw new IllegalArgumentException(code);
        };
    }

    static Stream<Arguments> patchMatrix() {
        List<Arguments> rows = new ArrayList<>();
        for (String user : List.of(TestUsers.ALICE, TestUsers.BOB, TestUsers.ADMIN)) {
            for (String code : List.of(ALICE_ACTIVE, ALICE_DEACTIVATED, ALICE_DELETED, BOB_ACTIVE, ADMIN_ACTIVE)) {
                for (boolean active : List.of(false, true)) {
                    rows.add(Arguments.of(user, code, active));
                }
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} PATCH active={2} on {1}")
    @MethodSource("patchMatrix")
    void shouldApplyOwnershipDeletionAndTransitionRulesToPatchForEveryCallerRowAndDirection(String user,
            String code, boolean active) throws Exception {
        LifecycleState before = data.lifecycleState(code);
        int expected = expectedStatus(user, code, active);
        Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MICROS);

        HttpResponse<String> response = patch(user, code, bodyFor(active));

        Instant windowEnd = Instant.now();
        assertThat(response.statusCode()).as("%s PATCH %s active=%s", user, code, active).isEqualTo(expected);
        LifecycleState after = data.lifecycleState(code);
        if (expected == 200) {
            assertThat(ApiClient.contentType(response)).startsWith(JSON);
            JsonNode body = json(response);
            assertThat(ApiClient.keys(body)).isEqualTo(RESOURCE_KEYS);
            assertThat(body.path("shortCode").asText()).isEqualTo(code);
            assertThat(body.path("status").asText()).isEqualTo(active ? "ACTIVE" : "DEACTIVATED");
            assertThat(body.path("customAlias").asBoolean()).isTrue();
            assertThat(after.status()).isEqualTo(active ? "ACTIVE" : "DEACTIVATED");
            assertThat(after.version()).isEqualTo(before.version() + 1);
            assertThat(after.updatedAt()).isBetween(windowStart, windowEnd);
            assertThat(after.deletedAt()).isNull();
            assertThat(after.deletedBy()).isNull();
            // The response is exactly what a GET sees afterwards.
            assertThat(json(get(TestUsers.ADMIN, code))).isEqualTo(body);
        } else {
            assertThat(after).as("row unchanged after %d", expected).isEqualTo(before);
            if (expected == 404) {
                assertNotFound(response, BASE + code);
                assertThat(withoutInstance(response))
                        .isEqualTo(withoutInstance(patch(user, "Unknown9", bodyFor(active))));
            } else {
                String expectedCode = active ? "SHORT_URL_ALREADY_ACTIVE" : "SHORT_URL_ALREADY_DEACTIVATED";
                assertProblem(response, 409, expectedCode, BASE + code);
            }
            if (!ALICE_DELETED.equals(code)) {
                assertRowStillAcceptsAWrite(ownerOf(code), code);
            }
        }
    }

    @Test
    void shouldReturn404ForAnUnknownAndAMalformedCodeOnPatchAndAdminDelete() throws Exception {
        for (String code : List.of("Unknown9", "ab", "not-a-code", "a".repeat(33))) {
            assertNotFound(patch(TestUsers.ALICE, code, INACTIVE_BODY), BASE + code);
            assertNotFound(patch(TestUsers.ADMIN, code, ACTIVE_BODY), BASE + code);
            assertNotFound(delete(TestUsers.ADMIN, code), BASE + code);
        }
        assertThat(data.rowCount()).isEqualTo(5);
    }

    @Test
    void shouldReturnByteIdentical404BodiesForPatchDeleteAndGetOnTheSamePath() throws Exception {
        for (String code : List.of(ALICE_DELETED, "Unknown9")) {
            HttpResponse<String> viaGet = get(TestUsers.ADMIN, code);
            HttpResponse<String> viaPatch = patch(TestUsers.ADMIN, code, INACTIVE_BODY);
            HttpResponse<String> viaDelete = delete(TestUsers.ADMIN, code);

            assertNotFound(viaGet, BASE + code);
            assertThat(viaPatch.body()).as("PATCH vs GET body for %s", code).isEqualTo(viaGet.body());
            assertThat(viaDelete.body()).as("DELETE vs GET body for %s", code).isEqualTo(viaGet.body());
            assertThat(ApiClient.contentType(viaPatch)).isEqualTo(ApiClient.contentType(viaGet));
            assertThat(ApiClient.contentType(viaDelete)).isEqualTo(ApiClient.contentType(viaGet));
        }
    }

    // ---- AC1 and AC2 with the database verified, and D2 ----

    @Test
    void shouldDeactivateThenReactivateRecordTheChangeAndToggleTheRedirect() throws Exception {
        String code = ALICE_ACTIVE;
        String location = "https://example.com/lifecycle/" + code;
        LifecycleState created = data.lifecycleState(code);
        assertThat(api.send("GET", "/" + code, null, null, null).statusCode()).as("control: redirects").isEqualTo(302);

        Instant deactivateStart = Instant.now().truncatedTo(ChronoUnit.MICROS);
        HttpResponse<String> deactivated = patch(TestUsers.ALICE, code, INACTIVE_BODY);
        Instant deactivateEnd = Instant.now();

        assertThat(deactivated.statusCode()).isEqualTo(200);
        assertThat(json(deactivated).path("status").asText()).isEqualTo("DEACTIVATED");
        LifecycleState afterDeactivate = data.lifecycleState(code);
        assertThat(afterDeactivate.status()).isEqualTo("DEACTIVATED");
        assertThat(afterDeactivate.version()).isEqualTo(created.version() + 1);
        assertThat(afterDeactivate.updatedAt()).isBetween(deactivateStart, deactivateEnd);
        HttpResponse<String> hidden = api.send("GET", "/" + code, null, null, null);
        assertNotFound(hidden, "/" + code);
        assertThat(hidden.headers().firstValue("Location")).isEmpty();
        assertThat(json(get(TestUsers.ALICE, code)).path("status").asText()).isEqualTo("DEACTIVATED");

        Instant reactivateStart = Instant.now().truncatedTo(ChronoUnit.MICROS);
        HttpResponse<String> reactivated = patch(TestUsers.ALICE, code, ACTIVE_BODY);
        Instant reactivateEnd = Instant.now();

        assertThat(reactivated.statusCode()).isEqualTo(200);
        assertThat(json(reactivated).path("status").asText()).isEqualTo("ACTIVE");
        LifecycleState afterReactivate = data.lifecycleState(code);
        assertThat(afterReactivate.status()).isEqualTo("ACTIVE");
        assertThat(afterReactivate.version()).isEqualTo(created.version() + 2);
        assertThat(afterReactivate.updatedAt()).isBetween(reactivateStart, reactivateEnd);
        HttpResponse<String> redirect = api.send("GET", "/" + code, null, null, null);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains(location);
    }

    // ---- AC9, AC10: the D26 conflicts ----

    @ParameterizedTest
    @CsvSource({
            "alice,AliceDea1,false,SHORT_URL_ALREADY_DEACTIVATED",
            "admin,AliceDea1,false,SHORT_URL_ALREADY_DEACTIVATED",
            "alice,AliceAct1,true,SHORT_URL_ALREADY_ACTIVE",
            "admin,AliceAct1,true,SHORT_URL_ALREADY_ACTIVE"})
    void shouldReturn409ForARedundantChangeAndLeaveTheRowUnchanged(String user, String code, boolean active,
            String errorCode) throws Exception {
        LifecycleState before = data.lifecycleState(code);

        HttpResponse<String> response = patch(user, code, bodyFor(active));

        assertProblem(response, 409, errorCode, BASE + code);
        assertThat(json(response).has("errors")).isFalse();
        assertThat(data.lifecycleState(code)).isEqualTo(before);
        // Positive control: the opposite change on the same row succeeds and bumps the version.
        HttpResponse<String> control = patch(user, code, bodyFor(!active));
        assertThat(control.statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(code).version()).isEqualTo(before.version() + 1);
    }

    // ---- AC6, AC8: USER DELETE is always 403 ----

    @ParameterizedTest
    @ValueSource(strings = {"alice", "bob"})
    void shouldReturn403ForAUserDeleteWhateverTheCodeAndChangeNothing(String user) throws Exception {
        List<String> codes = List.of(ALICE_ACTIVE, BOB_ACTIVE, ADMIN_ACTIVE, ALICE_DEACTIVATED, ALICE_DELETED,
                "Unknown9", "not-a-code");
        // The first five codes are rows; the last two never exist.
        List<LifecycleState> before = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            before.add(data.lifecycleState(codes.get(i)));
        }
        int rows = data.rowCount();
        JsonNode reference = null;

        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            HttpResponse<String> response = delete(user, code);

            assertProblem(response, 403, "ACCESS_DENIED", BASE + code);
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
            JsonNode body = withoutInstance(response);
            if (reference == null) {
                reference = body;
            }
            assertThat(body).as("403 body for %s equals the first one but for instance", code).isEqualTo(reference);
            if (i < before.size()) {
                assertThat(data.lifecycleState(code)).as("row %s unchanged", code).isEqualTo(before.get(i));
            }
        }
        assertThat(data.rowCount()).isEqualTo(rows);
        // Positive control: the same DELETE as ADMIN on the same row is accepted and changes it.
        LifecycleState active = data.lifecycleState(ALICE_ACTIVE);
        assertThat(delete(TestUsers.ADMIN, ALICE_ACTIVE).statusCode()).isEqualTo(204);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(active.version() + 1);
        assertThat(data.lifecycleState(ALICE_ACTIVE).status()).isEqualTo("DELETED");
    }

    // ---- AC5: ADMIN DELETE ----

    @ParameterizedTest
    @ValueSource(strings = {ALICE_ACTIVE, ALICE_DEACTIVATED, BOB_ACTIVE, ADMIN_ACTIVE})
    void shouldSoftDeleteAsAdminAndRecordWhoAndWhenInTheDatabase(String code) throws Exception {
        LifecycleState before = data.lifecycleState(code);
        String creator = data.createdBy(code);
        Instant windowStart = Instant.now().truncatedTo(ChronoUnit.MICROS);

        HttpResponse<String> response = delete(TestUsers.ADMIN, code);

        Instant windowEnd = Instant.now();
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DELETED");
        assertThat(after.deletedBy()).isEqualTo(TestUsers.ADMIN);
        assertThat(after.deletedAt()).isNotNull().isEqualTo(after.updatedAt());
        assertThat(after.deletedAt()).isBetween(windowStart, windowEnd);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(data.createdBy(code)).isEqualTo(creator);
        assertThat(data.countByCode(code)).isEqualTo(1);
    }

    @Test
    void shouldStoreTheConfiguredLowercaseUsernameAsDeletedByWhateverCaseTheClientTyped() throws Exception {
        String typedLogin = "Admin";
        String header = ApiClient.basicHeader(typedLogin, TestUsers.ADMIN);

        HttpResponse<String> response = delete(null, ALICE_ACTIVE, "Authorization", header);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(data.lifecycleState(ALICE_ACTIVE).deletedBy()).isEqualTo(TestUsers.ADMIN);
    }

    // ---- D1, D13, D46: after a delete ----

    @Test
    void shouldTreatADeletedCodeAsGoneEverywhereYetNeverAllowItsReuse() throws Exception {
        String code = ALICE_ACTIVE;
        assertThat(api.send("HEAD", "/" + code, null, null, null).statusCode()).as("control: HEAD reaches the row")
                .isEqualTo(302);
        assertThat(delete(TestUsers.ADMIN, code).statusCode()).isEqualTo(204);
        LifecycleState deleted = data.lifecycleState(code);
        HttpResponse<String> unknownGet = get(TestUsers.ADMIN, "Unknown9");

        for (String user : List.of(TestUsers.ALICE, TestUsers.ADMIN)) {
            HttpResponse<String> viaGet = get(user, code);
            assertNotFound(viaGet, BASE + code);
            assertThat(withoutInstance(viaGet)).isEqualTo(withoutInstance(unknownGet));
            assertNotFound(patch(user, code, ACTIVE_BODY), BASE + code);
            assertNotFound(patch(user, code, INACTIVE_BODY), BASE + code);
        }
        assertNotFound(delete(TestUsers.ADMIN, code), BASE + code);
        assertThat(data.lifecycleState(code)).as("nothing changed, deleted_at and deleted_by included")
                .isEqualTo(deleted);
        // Control: a live row on the same path shapes still accepts writes.
        assertRowStillAcceptsAWrite(TestUsers.BOB, BOB_ACTIVE);

        HttpResponse<String> anonymousGet = api.send("GET", "/" + code, null, null, null);
        assertNotFound(anonymousGet, "/" + code);
        assertThat(anonymousGet.headers().firstValue("Location")).isEmpty();
        assertThat(api.send("HEAD", "/" + code, null, null, null).statusCode()).isEqualTo(404);
        assertThat(json(api.send("GET", "/" + code, null, null, null)).path("errorCode").asText())
                .isEqualTo(NOT_FOUND);

        for (String user : List.of(TestUsers.ALICE, TestUsers.ADMIN)) {
            HttpResponse<String> reuse = api.post(user, api.createBody("https://example.com/reuse", code));
            assertProblem(reuse, 409, "ALIAS_ALREADY_EXISTS", "/api/v1/urls");
        }
        assertThat(data.countByCode(code)).isEqualTo(1);
        assertThat(data.countByOriginalUrl("https://example.com/reuse")).isZero();
    }

    // ---- D70: 406 changes nothing ----

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406AndChangeNothingWhenPatchAcceptIsUnacceptable(String accept) throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY, "Accept", accept);

        assertProblem(response, 406, "NOT_ACCEPTABLE", BASE + ALICE_ACTIVE);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        HttpResponse<String> control = patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY, "Accept", JSON);
        assertThat(control.statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
        assertThat(data.lifecycleState(ALICE_ACTIVE).status()).isEqualTo("DEACTIVATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json"})
    void shouldReturn406AndChangeNothingWhenAdminDeleteAcceptIsUnacceptable(String accept) throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = delete(TestUsers.ADMIN, ALICE_ACTIVE, "Accept", accept);

        assertProblem(response, 406, "NOT_ACCEPTABLE", BASE + ALICE_ACTIVE);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        assertThat(before.deletedAt()).isNull();
        HttpResponse<String> control = delete(TestUsers.ADMIN, ALICE_ACTIVE, "Accept", JSON);
        assertThat(control.statusCode()).isEqualTo(204);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
        assertThat(data.lifecycleState(ALICE_ACTIVE).status()).isEqualTo("DELETED");
    }

    @Test
    void shouldReturn406WithAnEmptyBodyForAnUnparseableAcceptOnPatchAndDeleteAndChangeNothing() throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> patched = patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY, "Accept", "foo");
        HttpResponse<String> deleted = delete(TestUsers.ADMIN, ALICE_ACTIVE, "Accept", "foo");

        assertThat(patched.statusCode()).isEqualTo(406);
        assertThat(patched.body()).isEmpty();
        assertThat(deleted.statusCode()).isEqualTo(406);
        assertThat(deleted.body()).isEmpty();
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        assertThat(patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY, "Accept", JSON).statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    @Test
    void shouldReturn406BeforeLookingUpTheCodeOnDeleteAndBeforeParsingTheBodyOnPatch() throws Exception {
        HttpResponse<String> missing = delete(TestUsers.ADMIN, "Unknown9", "Accept", "application/xml");
        HttpResponse<String> malformedBody = patch(TestUsers.ALICE, ALICE_ACTIVE, "{not json", "Accept",
                "application/xml");
        HttpResponse<String> textPlain = api.send("PATCH", BASE + ALICE_ACTIVE, TestUsers.ALICE, "text/plain",
                INACTIVE_BODY, "Accept", "application/xml");

        assertProblem(missing, 406, "NOT_ACCEPTABLE", BASE + "Unknown9");
        assertProblem(malformedBody, 406, "NOT_ACCEPTABLE", BASE + ALICE_ACTIVE);
        // 415 outranks 406 (D70 precedence).
        assertProblem(textPlain, 415, "UNSUPPORTED_MEDIA_TYPE", BASE + ALICE_ACTIVE);
        // Controls: with a valid Accept the same requests reach the layers they were expected to skip.
        assertProblem(delete(TestUsers.ADMIN, "Unknown9", "Accept", JSON), 404, NOT_FOUND, BASE + "Unknown9");
        assertProblem(patch(TestUsers.ALICE, ALICE_ACTIVE, "{not json", "Accept", JSON), 400, "MALFORMED_REQUEST",
                BASE + ALICE_ACTIVE);
    }

    @Test
    void shouldReturn403BeforeNegotiatingContentForAUserDeleteWithAnUnacceptableAccept() throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = delete(TestUsers.ALICE, ALICE_ACTIVE, "Accept", "application/xml");

        assertProblem(response, 403, "ACCESS_DENIED", BASE + ALICE_ACTIVE);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
    }

    // ---- D88: media type ----

    @ParameterizedTest
    @ValueSource(strings = {"application/merge-patch+json", "application/json-patch+json", "text/plain",
            "application/xml"})
    void shouldReturn415AndChangeNothingForAPatchMediaTypeOtherThanApplicationJson(String contentType)
            throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = api.send("PATCH", BASE + ALICE_ACTIVE, TestUsers.ALICE, contentType,
                INACTIVE_BODY);

        assertProblem(response, 415, "UNSUPPORTED_MEDIA_TYPE", BASE + ALICE_ACTIVE);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        assertThat(patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY).statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    @Test
    void shouldReturn415AndChangeNothingForAPatchWithoutAContentType() throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = api.send("PATCH", BASE + ALICE_ACTIVE, TestUsers.ALICE, null, INACTIVE_BODY);

        assertProblem(response, 415, "UNSUPPORTED_MEDIA_TYPE", BASE + ALICE_ACTIVE);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
    }

    @Test
    void shouldAcceptApplicationJsonWithACharsetParameter() throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = api.send("PATCH", BASE + ALICE_ACTIVE, TestUsers.ALICE,
                "application/json; charset=UTF-8", INACTIVE_BODY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    // ---- D89 and D34: the body ----

    @ParameterizedTest
    @ValueSource(strings = {"{\"active\":\"false\"}", "{\"active\":\"true\"}", "{\"active\":0}", "{\"active\":1}",
            "{\"active\":2}", "{\"active\":1.0}", "{\"active\":\"\"}", "{\"active\":\"maybe\"}", "{\"active\":{}}",
            "{\"active\":[]}", "{\"active\":[true]}", "{\"active\":false,\"x\":1}",
            "{\"expiresAt\":\"2030-01-01T00:00:00Z\"}", "{\"active\":false,\"active\":true}", "", "null", "[]",
            "{", "\"false\"", "false"})
    void shouldReturn400MalformedRequestForABodyThatIsNotAStrictBooleanDocumentAndChangeNothing(String body)
            throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = patch(TestUsers.ALICE, ALICE_ACTIVE, body);

        assertProblem(response, 400, "MALFORMED_REQUEST", BASE + ALICE_ACTIVE);
        assertThat(response.body()).doesNotContain("com.fasterxml").doesNotContain("Cannot deserialize")
                .doesNotContain("Unrecognized").doesNotContain("line:").doesNotContain("Exception");
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        // Positive control: a strict boolean on the same row is applied.
        assertThat(patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY).statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"active\":null}"})
    void shouldReturn400ValidationFailedNamingTheActiveFieldWhenItIsMissingOrNullAndChangeNothing(String body)
            throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = patch(TestUsers.ALICE, ALICE_ACTIVE, body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode problem = json(response);
        assertThat(problem.path("errorCode").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(ApiClient.keys(problem)).isEqualTo(Set.of("type", "title", "status", "detail", "instance",
                "errorCode", "errors"));
        assertThat(problem.path("errors")).hasSize(1);
        assertThat(problem.path("errors").get(0).path("field").asText()).isEqualTo("active");
        assertThat(problem.path("errors").get(0).path("message").asText()).isEqualTo("must not be null");
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        assertThat(patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY).statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    @Test
    void shouldReturn400ForABadBodyBeforeRevealingAnythingAboutWhetherTheCodeIsTheCallers() throws Exception {
        HttpResponse<String> foreign = patch(TestUsers.BOB, ALICE_ACTIVE, "{}");
        HttpResponse<String> unknown = patch(TestUsers.BOB, "Unknown9", "{}");

        assertThat(foreign.statusCode()).isEqualTo(400);
        assertThat(json(foreign).path("errorCode").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(withoutInstance(foreign)).isEqualTo(withoutInstance(unknown));
    }

    @Test
    void shouldStillCoerceNonBooleanScalarsOnCreateSoTheStrictBooleanRuleStaysScopedToPatch() throws Exception {
        String body = "{\"originalUrl\":\"https://example.com/numeric-alias\",\"alias\":12345}";

        HttpResponse<String> response = api.send("POST", "/api/v1/urls", TestUsers.ALICE, JSON, body);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode created = json(response);
        assertThat(created.path("shortCode").asText()).isEqualTo("12345");
        assertThat(created.path("customAlias").isBoolean()).as("booleans still serialise as JSON booleans")
                .isTrue();
        assertThat(created.path("customAlias").asBoolean()).isTrue();
        assertThat(data.countByCodeAndOwner("12345", TestUsers.ALICE)).isEqualTo(1);
    }

    // ---- D27, D90: click data is never written ----

    @Test
    void shouldNeverWriteClickDataOnDeactivateReactivateOrDeleteAndShowItInThePatchResponse() throws Exception {
        String code = ALICE_ACTIVE;
        data.seedClicks(code, 7, SEEDED_LAST_ACCESS);
        LifecycleState seeded = data.lifecycleState(code);
        assertThat(seeded.clickCount()).isEqualTo(7);
        assertThat(seeded.lastAccessedAt()).isEqualTo(SEEDED_LAST_ACCESS);

        HttpResponse<String> deactivated = patch(TestUsers.ALICE, code, INACTIVE_BODY);
        LifecycleState afterDeactivate = data.lifecycleState(code);
        assertThat(deactivated.statusCode()).isEqualTo(200);
        assertThat(json(deactivated).path("clickCount").asLong(-1)).isEqualTo(7);
        assertThat(Instant.parse(json(deactivated).path("lastAccessedAt").asText())).isEqualTo(SEEDED_LAST_ACCESS);
        assertClicksKept(afterDeactivate, seeded);
        assertThat(afterDeactivate.version()).isEqualTo(seeded.version() + 1);
        assertThat(afterDeactivate.updatedAt()).isAfter(seeded.updatedAt());

        HttpResponse<String> reactivated = patch(TestUsers.ALICE, code, ACTIVE_BODY);
        LifecycleState afterReactivate = data.lifecycleState(code);
        assertThat(reactivated.statusCode()).isEqualTo(200);
        assertThat(json(reactivated).path("clickCount").asLong(-1)).isEqualTo(7);
        assertClicksKept(afterReactivate, seeded);
        assertThat(afterReactivate.version()).isEqualTo(afterDeactivate.version() + 1);
        assertThat(afterReactivate.updatedAt()).isAfter(afterDeactivate.updatedAt());

        assertThat(delete(TestUsers.ADMIN, code).statusCode()).isEqualTo(204);
        LifecycleState afterDelete = data.lifecycleState(code);
        assertClicksKept(afterDelete, seeded);
        assertThat(afterDelete.version()).isEqualTo(afterReactivate.version() + 1);
        assertThat(afterDelete.updatedAt()).isAfter(afterReactivate.updatedAt());
        assertThat(afterDelete.status()).isEqualTo("DELETED");
    }

    private static void assertClicksKept(LifecycleState now, LifecycleState seeded) {
        assertThat(now.clickCount()).isEqualTo(seeded.clickCount());
        assertThat(now.lastAccessedAt()).isEqualTo(seeded.lastAccessedAt());
    }

    // ---- path variants ----

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST"})
    void shouldReturn405ListingTheMappedMethodsForOtherMethodsOnACodeAndChangeNothing(String method)
            throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        HttpResponse<String> response = api.send(method, BASE + ALICE_ACTIVE, TestUsers.ALICE, JSON, INACTIVE_BODY);

        assertProblem(response, 405, "METHOD_NOT_ALLOWED", BASE + ALICE_ACTIVE);
        Set<String> allow = Set.of(response.headers().firstValue("Allow").orElseThrow().split("\\s*,\\s*"));
        // Recorded from a real run: Spring lists the explicitly mapped methods and not the implicit HEAD.
        assertThat(allow).containsExactlyInAnyOrder("GET", "PATCH", "DELETE");
        assertThat(api.send("HEAD", BASE + ALICE_ACTIVE, TestUsers.ALICE, null, null).statusCode())
                .as("control: HEAD is served on the same path despite not being listed").isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        assertThat(patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY).statusCode()).isEqualTo(200);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    @Test
    void shouldReturnResourceNotFoundForTheTrailingSlashOnPatchAndAdminDeleteAndChangeNothing() throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);
        String slash = BASE + ALICE_ACTIVE + "/";

        HttpResponse<String> patched = api.send("PATCH", slash, TestUsers.ALICE, JSON, INACTIVE_BODY);
        HttpResponse<String> adminPatched = api.send("PATCH", slash, TestUsers.ADMIN, JSON, INACTIVE_BODY);
        HttpResponse<String> deleted = api.send("DELETE", slash, TestUsers.ADMIN, null, null);

        assertProblem(patched, 404, "RESOURCE_NOT_FOUND", slash);
        assertProblem(adminPatched, 404, "RESOURCE_NOT_FOUND", slash);
        assertProblem(deleted, 404, "RESOURCE_NOT_FOUND", slash);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        // Control: the same request without the slash is handled by the lifecycle handler.
        assertThat(delete(TestUsers.ADMIN, ALICE_ACTIVE).statusCode()).isEqualTo(204);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    @Test
    void shouldReturn403ForAUserDeleteWithATrailingSlash() throws Exception {
        String slash = BASE + ALICE_ACTIVE + "/";
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);

        assertProblem(api.send("DELETE", slash, TestUsers.ALICE, null, null), 403, "ACCESS_DENIED", slash);

        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
    }

    @Test
    void shouldReturn405WithAllowPostForDeleteOnTheCollectionForAdminAnd403ForAUser() throws Exception {
        HttpResponse<String> admin = api.send("DELETE", "/api/v1/urls", TestUsers.ADMIN, null, null);
        HttpResponse<String> user = api.send("DELETE", "/api/v1/urls", TestUsers.ALICE, null, null);

        assertProblem(admin, 405, "METHOD_NOT_ALLOWED", "/api/v1/urls");
        assertThat(admin.headers().firstValue("Allow")).contains("POST");
        assertProblem(user, 403, "ACCESS_DENIED", "/api/v1/urls");
        assertThat(data.rowCount()).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({"PATCH,alice", "PATCH,admin", "DELETE,alice", "DELETE,admin"})
    void shouldReturn403AccessDeniedForAnUpperCasedApiPathOnPatchAndDeleteAndChangeNothing(String method,
            String user) throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);
        String path = "/API/v1/urls/" + ALICE_ACTIVE;

        HttpResponse<String> response = api.send(method, path, user, "PATCH".equals(method) ? JSON : null,
                "PATCH".equals(method) ? INACTIVE_BODY : null);

        assertProblem(response, 403, "ACCESS_DENIED", path);
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        assertThat(patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY).statusCode()).isEqualTo(200);
    }

    // ---- AC12: authentication ----

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    void shouldReturn401ForAnonymousAndWrongCredentialsAndChangeNothing(String method) throws Exception {
        LifecycleState before = data.lifecycleState(ALICE_ACTIVE);
        String contentType = "PATCH".equals(method) ? JSON : null;
        String body = "PATCH".equals(method) ? INACTIVE_BODY : null;
        String path = BASE + ALICE_ACTIVE;
        String wrong = ApiClient.rawBasicHeader(TestUsers.ALICE, "not-the-password");

        HttpResponse<String> anonymous = api.send(method, path, null, contentType, body);
        HttpResponse<String> badPassword = api.send(method, path, null, contentType, body, "Authorization", wrong);
        HttpResponse<String> unknownCode = api.send(method, BASE + "Unknown9", null, contentType, body);

        for (HttpResponse<String> response : List.of(anonymous, badPassword)) {
            assertProblem(response, 401, "AUTHENTICATION_REQUIRED", path);
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                    challenge -> assertThat(challenge).startsWith("Basic"));
        }
        assertProblem(unknownCode, 401, "AUTHENTICATION_REQUIRED", BASE + "Unknown9");
        assertThat(data.lifecycleState(ALICE_ACTIVE)).isEqualTo(before);
        // Positive control: with valid credentials the same request is accepted.
        HttpResponse<String> control = "PATCH".equals(method)
                ? patch(TestUsers.ALICE, ALICE_ACTIVE, INACTIVE_BODY)
                : delete(TestUsers.ADMIN, ALICE_ACTIVE);
        assertThat(control.statusCode()).isEqualTo("PATCH".equals(method) ? 200 : 204);
        assertThat(data.lifecycleState(ALICE_ACTIVE).version()).isEqualTo(before.version() + 1);
    }

    // ---- logging ----

    @Test
    void shouldLogTheCodeButNeverAUsernameOrAUrlForPatchDeleteAndTheirErrors(CapturedOutput output)
            throws Exception {
        String code = "LogCheck1";
        String marker = "lifecycle-log-marker-7f3a";
        HttpResponse<String> created = api.post(TestUsers.ALICE,
                api.createBody("https://example.com/" + marker + "?token=secret-q", code));
        assertThat(created.statusCode()).isEqualTo(201);

        assertThat(patch(TestUsers.ALICE, code, INACTIVE_BODY).statusCode()).isEqualTo(200);
        assertThat(patch(TestUsers.ALICE, code, INACTIVE_BODY).statusCode()).isEqualTo(409);
        assertThat(patch(TestUsers.ALICE, code, ACTIVE_BODY).statusCode()).isEqualTo(200);
        assertThat(patch(TestUsers.BOB, code, INACTIVE_BODY).statusCode()).isEqualTo(404);
        assertThat(delete(TestUsers.ALICE, code).statusCode()).isEqualTo(403);
        assertThat(delete(TestUsers.ADMIN, code).statusCode()).isEqualTo(204);
        assertThat(delete(TestUsers.ADMIN, code).statusCode()).isEqualTo(404);

        String logs = output.getAll();
        // Positive capture: the success lines were logged, so the absences below are not vacuous.
        assertThat(logs).contains("Short URL deactivated: code=" + code)
                .contains("Short URL reactivated: code=" + code)
                .contains("Short URL deleted: code=" + code);
        assertThat(logs).doesNotContain(marker).doesNotContain("secret-q").doesNotContain("https://example.com/");
        assertThat(logs).doesNotContainPattern("(?i)\\b(alice|bob|admin)\\b");
        assertThat(logs).doesNotContain(" ERROR ").doesNotContain(" WARN ");
    }
}
