package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-006 through a real Tomcat and PostgreSQL: the contract of {@code POST /api/v1/urls}
 * (AC1-AC5, AC8, AC10-AC12, AC14, AC16, AC17). Every test uses its own marker URL or alias and
 * asserts on its own rows. Collisions, exhaustion and reserved-word retries are in
 * {@link ShortCodeCollisionIT}; concurrency is in {@link CreateShortUrlConcurrencyIT}.
 *
 * <p>Tests use marker URLs for their own assertions, but the table is also truncated before each test. That
 * truncation assumes serial execution, as the scripted generator seam does; enabling parallel execution means
 * revisiting truncation and the seam together.
 */
class CreateShortUrlIT extends IntegrationTestBase {

    private static final Set<String> RESOURCE_KEYS = new TreeSet<>(Set.of("shortCode", "shortUrl", "originalUrl",
            "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt"));
    private static final Set<String> PROBLEM_KEYS = new TreeSet<>(Set.of("type", "title", "status", "detail",
            "instance", "errorCode"));
    private static final Set<String> PROBLEM_WITH_ERRORS_KEYS;

    static {
        PROBLEM_WITH_ERRORS_KEYS = new TreeSet<>(PROBLEM_KEYS);
        PROBLEM_WITH_ERRORS_KEYS.add("errors");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedShortCodeGenerator generator;

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

    private HttpResponse<String> create(String user, String url, String alias, String... headers) throws Exception {
        return api.post(user, api.createBody(url, alias), headers);
    }

    private void assertProblem(HttpResponse<String> response, int status, String errorCode, Set<String> keys)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = api.json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(keys);
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("errorCode").asText()).isEqualTo(errorCode);
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/urls");
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("detail").asText()).isNotBlank();
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    // ---- AC1 ----

    @Test
    void shouldReturn201WithDocumentedResourceWhenNoAliasIsGiven() throws Exception {
        String url = marker("ac1");

        HttpResponse<String> response = create(TestUsers.ALICE, url, null);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
        JsonNode body = api.json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(RESOURCE_KEYS);
        String code = body.path("shortCode").asText();
        assertThat(code).matches("^[A-Za-z0-9]{7}$");
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/urls/" + code);
        assertThat(body.path("shortUrl").asText()).isEqualTo("https://short.example/" + code);
        assertThat(body.path("originalUrl").asText()).isEqualTo(url);
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("customAlias").isBoolean()).isTrue();
        assertThat(body.path("customAlias").asBoolean()).isFalse();
        assertThat(body.path("clickCount").isIntegralNumber()).isTrue();
        assertThat(body.path("clickCount").asLong()).isZero();
        assertThat(body.get("lastAccessedAt").isNull()).isTrue();
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
        Instant stored = jdbc.queryForObject("SELECT created_at FROM short_url WHERE short_code = ?",
                OffsetDateTime.class, code).toInstant();
        assertThat(Instant.parse(body.path("createdAt").asText())).isEqualTo(stored);
    }

    @Test
    void shouldStoreAndReturnTheOriginalUrlExactlyAsSubmitted() throws Exception {
        String url = "HTTPS://Example.COM/a%20b?Q=1&r=%7E#Frag/" + UUID.randomUUID();

        HttpResponse<String> response = create(TestUsers.ALICE, url, null);

        assertThat(response.statusCode()).isEqualTo(201);
        String code = api.json(response).path("shortCode").asText();
        assertThat(api.json(response).path("originalUrl").asText()).isEqualTo(url);
        assertThat(jdbc.queryForObject("SELECT original_url FROM short_url WHERE short_code = ?", String.class, code))
                .isEqualTo(url);
    }

    // ---- AC2 ----

    @Test
    void shouldUseTheSubmittedAliasAsShortCodeAndFlagItCustom() throws Exception {
        HttpResponse<String> response = create(TestUsers.ALICE, marker("ac2"), "Promo2026");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = api.json(response);
        assertThat(body.path("shortCode").asText()).isEqualTo("Promo2026");
        assertThat(body.path("customAlias").asBoolean()).isTrue();
        assertThat(body.path("shortUrl").asText()).isEqualTo("https://short.example/Promo2026");
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/urls/Promo2026");
        assertThat(generator.calls()).as("a custom alias never asks the generator").isZero();
        assertThat(jdbc.queryForObject("SELECT custom_alias FROM short_url WHERE short_code = 'Promo2026'",
                Boolean.class)).isTrue();
    }

    @Test
    void shouldAcceptAliasesAtTheLengthLimits() throws Exception {
        assertThat(create(TestUsers.ALICE, marker("ac2-min"), "abc").statusCode()).isEqualTo(201);
        assertThat(create(TestUsers.ALICE, marker("ac2-max"), "a".repeat(32)).statusCode()).isEqualTo(201);
    }

    @Test
    void shouldLetAdminCreateThroughTheRoleHierarchy() throws Exception {
        HttpResponse<String> response = create(TestUsers.ADMIN, marker("admin"), null);

        assertThat(response.statusCode()).isEqualTo(201);
    }

    // ---- AC3 ----

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DEACTIVATED", "DELETED"})
    void shouldReturn409WhenAliasAlreadyExistsInAnyStatus(String status) throws Exception {
        String alias = "Taken" + status.substring(0, 3);
        data.seed(alias, status);
        String url = marker("ac3");

        HttpResponse<String> response = create(TestUsers.ALICE, url, alias);

        assertProblem(response, 409, "ALIAS_ALREADY_EXISTS", PROBLEM_KEYS);
        assertThat(response.body()).doesNotContain("uk_short_url").doesNotContain("constraint")
                .doesNotContain("Exception");
        assertThat(data.countByCode(alias)).as("the pre-existing row is untouched").isEqualTo(1);
        assertThat(data.createdBy(alias)).isEqualTo(ShortUrlTestData.SEED_OWNER);
        assertThat(data.countByOriginalUrl(url)).isZero();
        assertThat(generator.calls()).as("no retry for custom aliases").isZero();
    }

    // ---- AC4 ----

    @ParameterizedTest
    @ValueSource(strings = {"ab", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "promo_1", "api", "API", "Health", "", "   ",
            "abc ", " abc", "ab\n", "prömo"})
    void shouldReturn400InvalidAliasWithFieldAndNoEchoedValue(String alias) throws Exception {
        String url = marker("ac4");

        HttpResponse<String> response = create(TestUsers.ALICE, url, alias);

        assertProblem(response, 400, "INVALID_ALIAS", PROBLEM_WITH_ERRORS_KEYS);
        JsonNode errors = api.json(response).path("errors");
        assertThat(errors).hasSize(1);
        assertThat(ApiClient.keys(errors.get(0))).isEqualTo(new TreeSet<>(Set.of("field", "message")));
        assertThat(errors.get(0).path("field").asText()).isEqualTo("alias");
        if (alias.length() > 3) {
            assertThat(response.body()).as("rejected value is not echoed").doesNotContain(alias);
        }
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    // ---- AC5 ----

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/file", "javascript:alert(1)", "example.com/no-scheme",
            "https://u:p@example.com/x", "https://short.example/x", "HTTPS://SHORT.EXAMPLE./x",
            "https://bücher.example/x", " https://example.com/padded", "https://example.com/space "})
    void shouldReturn400InvalidUrlWithFieldAndStoreNothing(String url) throws Exception {
        HttpResponse<String> response = create(TestUsers.ALICE, url, null);

        assertProblem(response, 400, "INVALID_URL", PROBLEM_WITH_ERRORS_KEYS);
        JsonNode errors = api.json(response).path("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("field").asText()).isEqualTo("originalUrl");
        assertThat(response.body()).as("rejected value is not echoed").doesNotContain("example.com/file")
                .doesNotContain("u:p@");
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    @Test
    void shouldReturn400InvalidUrlWhenUrlExceeds2048Characters() throws Exception {
        String prefix = "https://example.com/";
        String tooLong = prefix + "a".repeat(2049 - prefix.length());
        String atLimit = prefix + "a".repeat(2048 - prefix.length());

        HttpResponse<String> rejected = create(TestUsers.ALICE, tooLong, null);
        HttpResponse<String> accepted = create(TestUsers.ALICE, atLimit, null);

        assertProblem(rejected, 400, "INVALID_URL", PROBLEM_WITH_ERRORS_KEYS);
        assertThat(data.countByOriginalUrl(tooLong)).isZero();
        assertThat(accepted.statusCode()).as("2048 characters is accepted (positive control)").isEqualTo(201);
        assertThat(data.countByOriginalUrl(atLimit)).isEqualTo(1);
    }

    @Test
    void shouldReportInvalidUrlFirstWhenUrlAndAliasAreBothInvalid() throws Exception {
        HttpResponse<String> response = create(TestUsers.ALICE, "ftp://example.com/x", "ab");

        assertProblem(response, 400, "INVALID_URL", PROBLEM_WITH_ERRORS_KEYS);
    }

    // ---- AC8 ----

    @Test
    void shouldReturn401AndCreateNothingWhenNoCredentialsAreSent() throws Exception {
        String url = marker("ac8");

        HttpResponse<String> response = create(null, url, "anon001");

        assertProblem(response, 401, "AUTHENTICATION_REQUIRED", PROBLEM_KEYS);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(data.countByOriginalUrl(url)).isZero();
        assertThat(data.countByCode("anon001")).isZero();
    }

    // ---- AC10 ----

    @ParameterizedTest
    @CsvSource({"alice,alice", "ALICE,alice", "Bob,bob", "admin,admin"})
    void shouldRecordConfiguredUsernameAsCreatorAndNeverReturnIt(String login, String owner) throws Exception {
        HttpResponse<String> response = create(login, marker("ac10"), null);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(data.createdBy(api.json(response).path("shortCode").asText())).isEqualTo(owner);
        assertThat(response.body()).doesNotContain("createdBy").doesNotContain("created_by");
        assertThat(ApiClient.keys(api.json(response))).isEqualTo(RESOURCE_KEYS);
    }

    // ---- AC11 ----

    @Test
    void shouldCreateTwoDifferentShortCodesForTheSameUrl() throws Exception {
        String url = marker("ac11");

        HttpResponse<String> first = create(TestUsers.ALICE, url, null);
        HttpResponse<String> second = create(TestUsers.ALICE, url, null);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(api.json(first).path("shortCode").asText())
                .isNotEqualTo(api.json(second).path("shortCode").asText());
        assertThat(data.countByOriginalUrl(url)).isEqualTo(2);
    }

    // ---- AC12 ----

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"originalUrl\":null}", "{\"originalUrl\":\"\"}", "{\"originalUrl\":\"   \"}",
            "{\"alias\":\"valid123\"}"})
    void shouldReturn400ValidationFailedWhenOriginalUrlIsMissingOrBlank(String body) throws Exception {
        HttpResponse<String> response = api.post(TestUsers.ALICE, body);

        assertProblem(response, 400, "VALIDATION_FAILED", PROBLEM_WITH_ERRORS_KEYS);
        JsonNode errors = api.json(response).path("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("field").asText()).isEqualTo("originalUrl");
        assertNoInternals(response);
        assertThat(data.countByCode("valid123")).isZero();
    }

    // ---- AC14 ----

    @ParameterizedTest
    @ValueSource(strings = {"{\"originalUrl\":", "", "null", "[]", "not json", "{\"originalUrl\":{}}",
            "{\"originalUrl\":\"https://example.com/a\",\"alais\":\"typo123\"}",
            "{\"originalUrl\":\"https://example.com/a\",\"alias\":\"abc\",\"alias\":\"abd\"}",
            "{\"originalUrl\":\"https://example.com/a\",\"alias\":[\"abc\"]}"})
    void shouldReturn400MalformedRequestWithoutParserDetails(String body) throws Exception {
        HttpResponse<String> response = api.post(TestUsers.ALICE, body);

        assertProblem(response, 400, "MALFORMED_REQUEST", PROBLEM_KEYS);
        assertNoInternals(response);
        assertThat(data.countByOriginalUrl("https://example.com/a")).isZero();
    }

    private void assertNoInternals(HttpResponse<String> response) {
        for (String marker : List.of("Exception", "com.fasterxml", "com.schwab", "org.springframework",
                "org.hibernate", "org.postgresql", "JSON parse", "Unexpected", "at line", "column", "stackTrace",
                "Caused by")) {
            assertThat(response.body()).as("body must not contain %s", marker).doesNotContain(marker);
        }
    }

    // ---- Other framework errors (D61) and precedence (D70) ----

    @Test
    void shouldReturn405WithAllowHeaderForOtherMethodsOnTheCollection() throws Exception {
        for (String method : List.of("GET", "PUT")) {
            HttpResponse<String> response = api.send(method, "/api/v1/urls", TestUsers.ALICE, null, null);

            assertProblem(response, 405, "METHOD_NOT_ALLOWED", PROBLEM_KEYS);
            assertThat(response.headers().firstValue("Allow").orElse("")).contains("POST");
        }
    }

    @Test
    void shouldReturn415WhenContentTypeIsNotJsonAndCreateNothing() throws Exception {
        String url = marker("415");

        HttpResponse<String> response = api.send("POST", "/api/v1/urls", TestUsers.ALICE, "text/plain",
                api.createBody(url, null));

        assertProblem(response, 415, "UNSUPPORTED_MEDIA_TYPE", PROBLEM_KEYS);
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    @Test
    void shouldReturn415BeforeNotAcceptableWhenBothContentTypeAndAcceptAreWrong() throws Exception {
        HttpResponse<String> response = api.send("POST", "/api/v1/urls", TestUsers.ALICE, "text/plain",
                api.createBody(marker("prec"), null), "Accept", "application/xml");

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void shouldReturn406BeforeBadRequestWhenBodyIsMalformedAndAcceptIsUnacceptable() throws Exception {
        HttpResponse<String> response = api.post(TestUsers.ALICE, "{\"originalUrl\":", "Accept", "application/xml");

        assertThat(response.statusCode()).isEqualTo(406);
    }

    @Test
    void shouldReturn401BeforeNotAcceptableForAnonymousCallerWithUnacceptableAccept() throws Exception {
        HttpResponse<String> response = create(null, marker("prec401"), null, "Accept", "application/xml");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    // ---- AC16 ----

    @Test
    void shouldBuildShortUrlFromConfiguredBaseUrlNotFromForgedHostHeader() throws Exception {
        HttpResponse<String> response = create(TestUsers.ALICE, marker("ac16"), null, "Host", "attacker.example");

        assertThat(response.statusCode()).isEqualTo(201);
        String code = api.json(response).path("shortCode").asText();
        assertThat(api.json(response).path("shortUrl").asText()).isEqualTo("https://short.example/" + code);
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/urls/" + code);
        assertThat(response.body()).doesNotContain("attacker.example");
        assertThat(response.headers().map().toString()).doesNotContain("attacker.example");
    }

    @Test
    void shouldReachTomcatWithForgedHostHeaderSoTheAc16TestIsNotVacuous() throws Exception {
        String url = marker("ac16-control");

        HttpResponse<String> response = create(TestUsers.ALICE, url, null, "Host", "bad host");

        assertThat(response.statusCode()).isEqualTo(400);
        // The 400 came from Tomcat itself, not from the application's problem+json advice.
        assertThat(ApiClient.contentType(response)).doesNotStartWith("application/problem+json");
        assertThat(response.body()).doesNotContain("errorCode");
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    @Test
    void shouldIgnoreForwardedHostHeaderWhenBuildingShortUrl() throws Exception {
        HttpResponse<String> response = create(TestUsers.ALICE, marker("ac16-xfh"), null,
                "X-Forwarded-Host", "attacker.example", "X-Forwarded-Proto", "http", "Forwarded",
                "host=attacker.example");

        assertThat(response.statusCode()).isEqualTo(201);
        String code = api.json(response).path("shortCode").asText();
        assertThat(api.json(response).path("shortUrl").asText()).isEqualTo("https://short.example/" + code);
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/urls/" + code);
        assertThat(response.body()).doesNotContain("attacker.example");
        assertThat(response.headers().map().toString()).doesNotContain("attacker.example");
    }

    // ---- AC17 ----

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/plain", "application/problem+json", "image/png"})
    void shouldReturn406AndCreateNothingWhenAcceptIsUnacceptable(String accept) throws Exception {
        String url = marker("ac17");

        HttpResponse<String> refused = create(TestUsers.ALICE, url, null, "Accept", accept);

        assertProblem(refused, 406, "NOT_ACCEPTABLE", PROBLEM_KEYS);
        assertThat(data.countByOriginalUrl(url)).as("nothing is created for an unacceptable Accept").isZero();

        // Positive control: the very same request with an acceptable Accept creates exactly one row,
        // so the zero above is not a vacuous result of a broken request.
        HttpResponse<String> accepted = create(TestUsers.ALICE, url, null, "Accept", "application/json");

        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat(ApiClient.contentType(accepted)).startsWith("application/json");
        assertThat(ApiClient.contentType(accepted)).doesNotContain("problem");
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/*", "application/json;q=0.9, text/plain;q=0.1",
            "text/plain, application/json"})
    void shouldCreateExactlyOneRowWhenAcceptAllowsJson(String accept) throws Exception {
        String url = marker("ac17-ok");

        HttpResponse<String> response = create(TestUsers.ALICE, url, null, "Accept", accept);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
    }

    @Test
    void shouldFreeTheAliasWhenTheFirstRequestWasRefusedForItsAcceptHeader() throws Exception {
        String url = marker("ac17-alias");

        HttpResponse<String> refused = create(TestUsers.ALICE, url, "Reuse001", "Accept", "application/xml");
        assertThat(refused.statusCode()).isEqualTo(406);
        assertThat(data.countByCode("Reuse001")).isZero();

        HttpResponse<String> retried = create(TestUsers.ALICE, url, "Reuse001", "Accept", "application/json");

        assertThat(retried.statusCode()).as("201, not 409").isEqualTo(201);
        assertThat(api.json(retried).path("shortCode").asText()).isEqualTo("Reuse001");
        assertThat(data.countByCode("Reuse001")).isEqualTo(1);
    }

    @Test
    void shouldReturn406WithEmptyBodyAndCreateNothingWhenAcceptIsUnparseable() throws Exception {
        String url = marker("ac17-foo");

        HttpResponse<String> refused = create(TestUsers.ALICE, url, null, "Accept", "foo");

        // D70 known deviation: an unparseable Accept gets 406 with an empty body (pinned on purpose).
        assertThat(refused.statusCode()).isEqualTo(406);
        assertThat(refused.body()).isEmpty();
        assertThat(refused.headers().firstValue("Location")).isEmpty();
        assertThat(data.countByOriginalUrl(url)).isZero();

        HttpResponse<String> control = create(TestUsers.ALICE, url, null, "Accept", "application/json");
        assertThat(control.statusCode()).isEqualTo(201);
        assertThat(data.countByOriginalUrl(url)).isEqualTo(1);
    }
}
