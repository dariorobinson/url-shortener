package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schwab.urlshortener.validation.LocationEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * US-008 acceptance criteria through a real Tomcat and Testcontainers PostgreSQL, with redirects never
 * followed: the exact Location (D75), the caching headers (D76), HEAD (AC7), the indistinguishable 404
 * (D2, D72, D74), routing precedence (AC5, D78), content negotiation (D70), the ignored query (D79),
 * invalid credentials (D55), the trailing slash (D80) and "reads write nothing". Every 404 assertion also
 * asserts the errorCode, so an unmapped path (RESOURCE_NOT_FOUND) cannot satisfy a test that wants
 * SHORT_URL_NOT_FOUND, and every HEAD 404 has a same-path 302 control. Rows are truncated before each
 * test only, and execution is assumed serial. Credentials come from {@link TestUsers} and are never printed.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedirectIT extends IntegrationTestBase {

    private static final String NO_STORE = "no-store";
    private static final String SECURITY_DEFAULT_CACHE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final Set<String> PROBLEM_KEYS =
            Set.of("type", "title", "status", "detail", "instance", "errorCode");
    private static final String NOT_FOUND = "SHORT_URL_NOT_FOUND";
    private static final String ACTIVE_CODE = "Active01";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedShortCodeGenerator generator;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void truncate() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        generator.reset();
    }

    @AfterEach
    void resetGenerator() {
        generator.reset();
    }

    // ---- helpers ----

    private HttpResponse<String> call(String method, String path, String... headers) throws Exception {
        return api.send(method, path, null, null, null, headers);
    }

    private HttpResponse<String> callAs(String method, String path, String user) throws Exception {
        return api.send(method, path, user, null, null);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return api.json(response);
    }

    /** POSTs the URL as alice and returns the generated short code, asserting the stored value round-trips. */
    private String create(String url) throws Exception {
        HttpResponse<String> created = api.post(TestUsers.ALICE, api.createBody(url, null));
        assertThat(created.statusCode()).as("create %s", url).isEqualTo(201);
        assertThat(json(created).path("originalUrl").asText()).isEqualTo(url);
        return json(created).path("shortCode").asText();
    }

    private void assertRedirectsTo(HttpResponse<String> response, String expectedLocation) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().allValues("Location")).containsExactly(expectedLocation);
    }

    private void assertNotFound(HttpResponse<String> response, String rawPath) throws Exception {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(body.path("errorCode").asText()).isEqualTo(NOT_FOUND);
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
        assertThat(body.path("instance").asText()).isEqualTo(rawPath);
    }

    /** Headers except Date, keyed case-insensitively, for equality across responses. */
    private static Map<String, List<String>> stableHeaders(HttpResponse<String> response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((name, values) -> {
            if (!"date".equalsIgnoreCase(name)) {
                headers.put(name, values);
            }
        });
        return headers;
    }

    private static Map<String, List<String>> withoutContentLength(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        copy.remove("content-length");
        return copy;
    }

    /** Status line of a raw request target the JDK client would refuse to build (for example with braces). */
    private int rawStatus(String target) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            assertThat(statusLine).startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    // ---- AC1: exact Location ----

    static List<String> asciiUrls() {
        return List.of(
                "https://example.com/path?a=1&b=two+words&c=x%3Dy",
                "https://example.com/page#section-2",
                "https://example.com/a%2fb/c%2Fd",
                "https://example.com/a%20b",
                "HTTPS://EXAMPLE.COM/Path/CaseSensitive",
                "https://example.com/a/../b/./c",
                "https://example.com",
                "https://example.com:8443/with/port",
                "https://[2001:db8::1]:8080/ipv6",
                "https://example.com/trailing?",
                "https://example.com/trailing#",
                "http://example.com/plain-http",
                "https://example.com/" + "a".repeat(2028));
    }

    @ParameterizedTest
    @MethodSource("asciiUrls")
    void shouldRedirectToTheStoredAsciiUrlByteForByte(String url) throws Exception {
        String code = create(url);

        HttpResponse<String> response = call("GET", "/" + code);

        assertRedirectsTo(response, url);
        String stored = jdbc.queryForObject("SELECT original_url FROM short_url WHERE short_code = ?",
                String.class, code);
        assertThat(stored).isEqualTo(url);
    }

    static List<String[]> nonAsciiUrls() {
        return List.of(
                new String[] {"https://example.com/café?q=ü#ß",
                        "https://example.com/caf%C3%A9?q=%C3%BC#%C3%9F"},
                new String[] {"https://example.com/😀", "https://example.com/%F0%9F%98%80"},
                new String[] {"https://example.com/中", "https://example.com/%E4%B8%AD"},
                new String[] {"https://example.com/é", "https://example.com/%C3%A9"},
                new String[] {"https://example.com/é", "https://example.com/e%CC%81"},
                new String[] {"https://example.com/%C3%A9é", "https://example.com/%C3%A9%C3%A9"});
    }

    @ParameterizedTest
    @MethodSource("nonAsciiUrls")
    void shouldPercentEncodeNonAsciiAsUtf8InLocationAndKeepTheHeaderPresent(String stored, String expected,
            CapturedOutput output) throws Exception {
        String code = create(stored);

        HttpResponse<String> response = call("GET", "/" + code);

        // Positive control: the header is present and exactly the encoded form (Tomcat would drop it otherwise).
        assertRedirectsTo(response, expected);
        assertThat(output.getAll()).doesNotContain("has been removed from the response because it is invalid")
                .doesNotContain("example.com/caf").doesNotContain(stored);
    }

    // ---- D84: the new maximum ----

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void shouldRedirectWithExactLocationAndNoStoreAndNoTomcatErrorAtTheD84MaximumOf2048EncodedBytes(String method,
            CapturedOutput output) throws Exception {
        String url = EncodedUrls.withEncodedLength(EncodedUrls.CJK, 2048);
        assertThat(LocationEncoder.encode(url)).hasSize(2048);
        assertThat(url.chars().filter(c -> c == '\u4E2D').count()).as("mostly CJK").isGreaterThan(200);
        String code = create(url);
        String stored = jdbc.queryForObject("SELECT original_url FROM short_url WHERE short_code = ?",
                String.class, code);
        assertThat(stored).isEqualTo(url);

        HttpResponse<String> response = call(method, "/" + code);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().allValues("Location")).hasSize(1);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).hasSize(2048).isEqualTo(LocationEncoder.encode(stored));
        assertThat(location.getBytes(StandardCharsets.US_ASCII))
                .isEqualTo(LocationEncoder.encode(stored).getBytes(StandardCharsets.US_ASCII));
        assertThat(response.headers().allValues("Cache-Control")).containsExactly(NO_STORE);
        assertThat(output.getAll()).doesNotContain("HeadersTooLargeException").doesNotContain(" ERROR ");
    }

    @Test
    void shouldLogTomcatHeadersTooLargeErrorWhenAnOversizedRowBypassesD84(CapturedOutput output) throws Exception {
        // Positive control for the log assertion above: the same capture does see the Tomcat failure when a
        // row that D84 would reject is seeded by raw SQL (about 18 KB of Location, over the 8 KB header buffer).
        String oversized = EncodedUrls.PREFIX + EncodedUrls.CJK.repeat(2028);
        data.seed("Huge0001", "ACTIVE", oversized);

        HttpResponse<String> response = call("GET", "/Huge0001");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(output.getAll()).contains("HeadersTooLargeException");
    }

    @Test
    void shouldRejectAt900CjkCharactersWhichWouldHaveBeenEightKilobytesOfLocation() throws Exception {
        String url = EncodedUrls.PREFIX + EncodedUrls.CJK.repeat(900);
        assertThat(LocationEncoder.encode(url).length()).isGreaterThan(8000);

        HttpResponse<String> response = api.post(TestUsers.ALICE, api.createBody(url, null));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("errorCode").asText()).isEqualTo("INVALID_URL");
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    @Test
    void shouldEncodeControlCharactersAndSpacesSoTheyCanNeverReachTheHeader() throws Exception {
        // Only reachable through raw SQL, because java.net.URI rejects them at create.
        data.seed("Ctl12345", "ACTIVE", "https://example.com/a b\r\nX-Injected: 1");

        HttpResponse<String> response = call("GET", "/Ctl12345");

        assertRedirectsTo(response, "https://example.com/a%20b%0D%0AX-Injected:%201");
        assertThat(response.headers().firstValue("X-Injected")).isEmpty();
    }

    // ---- AC1 / D76: caching headers, body ----

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void shouldSendExactlyNoStoreAndNoPragmaOrExpiresOn302(String method) throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/cache");

        HttpResponse<String> response = call(method, "/" + ACTIVE_CODE);

        assertRedirectsTo(response, "https://example.com/cache");
        assertThat(response.headers().allValues("Cache-Control")).containsExactly(NO_STORE);
        assertThat(response.headers().allValues("Pragma")).isEmpty();
        assertThat(response.headers().allValues("Expires")).isEmpty();
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isEmpty();
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void shouldGiveThe404TheSecurityDefaultCachingHeaders() throws Exception {
        HttpResponse<String> response = call("GET", "/Nothing1");

        assertNotFound(response, "/Nothing1");
        assertThat(response.headers().allValues("Cache-Control")).containsExactly(SECURITY_DEFAULT_CACHE);
        assertThat(response.headers().allValues("Pragma")).containsExactly("no-cache");
        assertThat(response.headers().allValues("Expires")).containsExactly("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void shouldSend302WithAnEmptyBodyAndNoContentType(String method) throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/body");

        HttpResponse<String> response = call(method, "/" + ACTIVE_CODE);

        assertRedirectsTo(response, "https://example.com/body");
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("Content-Type")).isEmpty();
        response.headers().firstValue("Content-Length").ifPresent(length -> assertThat(length).isEqualTo("0"));
    }

    // ---- AC7: HEAD ----

    @Test
    void shouldAnswerHeadWithTheSameStatusAndHeadersAsGetApartFromDate() throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/head?x=1#f");

        HttpResponse<String> get = call("GET", "/" + ACTIVE_CODE);
        HttpResponse<String> head = call("HEAD", "/" + ACTIVE_CODE);

        assertRedirectsTo(get, "https://example.com/head?x=1#f");
        assertRedirectsTo(head, "https://example.com/head?x=1#f");
        assertThat(head.body()).isEmpty();
        assertThat(withoutContentLength(stableHeaders(head))).isEqualTo(withoutContentLength(stableHeaders(get)));
        assertThat(head.headers().allValues("Cache-Control")).containsExactly(NO_STORE);
    }

    // ---- AC2 to AC4: the 404 is indistinguishable ----

    @Test
    void shouldReturnByteIdentical404ForAbsentDeactivatedAndDeletedCodeOnTheSameGetPath() throws Exception {
        String code = "Same1234";
        String path = "/" + code;
        HttpResponse<String> absent = call("GET", path);
        data.seed(code, "ACTIVE", "https://example.com/same");
        HttpResponse<String> control = call("GET", path);
        data.setStatus(code, "DEACTIVATED");
        HttpResponse<String> deactivated = call("GET", path);
        data.markDeleted(code);
        HttpResponse<String> deleted = call("GET", path);

        assertRedirectsTo(control, "https://example.com/same");
        for (HttpResponse<String> response : List.of(absent, deactivated, deleted)) {
            assertNotFound(response, path);
            assertThat(response.body()).isEqualTo(absent.body());
            assertThat(stableHeaders(response)).isEqualTo(stableHeaders(absent));
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
    }

    @Test
    void shouldReturnIdentical404ForAbsentDeactivatedAndDeletedCodeOnTheSameHeadPath() throws Exception {
        String code = "Same5678";
        String path = "/" + code;
        HttpResponse<String> absent = call("HEAD", path);
        data.seed(code, "ACTIVE", "https://example.com/same-head");
        HttpResponse<String> control = call("HEAD", path);
        data.setStatus(code, "DEACTIVATED");
        HttpResponse<String> deactivated = call("HEAD", path);
        data.markDeleted(code);
        HttpResponse<String> deleted = call("HEAD", path);

        // Same-path control: the 302 proves HEAD reaches the redirect on this exact path.
        assertRedirectsTo(control, "https://example.com/same-head");
        for (HttpResponse<String> response : List.of(absent, deactivated, deleted)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEmpty();
            assertThat(stableHeaders(response)).isEqualTo(stableHeaders(absent));
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
        // The GET body of the same path names the cause SHORT_URL_NOT_FOUND (a HEAD has no body to assert).
        assertNotFound(call("GET", path), path);
    }

    static List<String> malformedCodes() {
        return List.of("ab", "a".repeat(33), "a_b", "a-b", "ab%20c", "caf%C3%A9", "abc.json", "favicon.ico",
                "%41%42");
    }

    @ParameterizedTest
    @MethodSource("malformedCodes")
    void shouldReturnTheSame404BodyExceptInstanceForMalformedCodes(String code) throws Exception {
        HttpResponse<String> reference = call("GET", "/Nothing1");
        HttpResponse<String> response = call("GET", "/" + code);

        assertNotFound(response, "/" + code);
        ObjectNode expected = (ObjectNode) json(reference).deepCopy();
        ObjectNode actual = (ObjectNode) json(response).deepCopy();
        expected.remove("instance");
        actual.remove("instance");
        assertThat(actual).isEqualTo(expected);
        assertThat(stableHeaders(response)).isEqualTo(stableHeaders(reference));
    }

    @ParameterizedTest
    @MethodSource("malformedCodes")
    void shouldReturn404WithoutABodyForHeadOnMalformedCodesWhileHeadOnAWellFormedActiveCodeRedirects(String code)
            throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/control");

        HttpResponse<String> control = call("HEAD", "/" + ACTIVE_CODE);
        HttpResponse<String> response = call("HEAD", "/" + code);

        assertRedirectsTo(control, "https://example.com/control");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertNotFound(call("GET", "/" + code), "/" + code);
    }

    // ---- D6, D48: case sensitivity, existing reserved word ----

    @Test
    void shouldTreatCodesDifferingOnlyInCaseAsDifferentLinks() throws Exception {
        data.seed("Mixed1", "ACTIVE", "https://example.com/upper");
        data.seed("mixed1", "ACTIVE", "https://example.com/lower");

        assertRedirectsTo(call("GET", "/Mixed1"), "https://example.com/upper");
        assertRedirectsTo(call("GET", "/mixed1"), "https://example.com/lower");
        assertNotFound(call("GET", "/MIXED1"), "/MIXED1");
    }

    @Test
    void shouldRedirectAnExistingActiveCodeThatIsNowAReservedWord() throws Exception {
        data.seed("Health", "ACTIVE", "https://example.com/health-page");

        assertRedirectsTo(call("GET", "/Health"), "https://example.com/health-page");
    }

    // ---- Content negotiation (D70, D81) ----

    static List<String> acceptValues() {
        List<String> values = new ArrayList<>(List.of("text/html", "image/png", "*/*", "application/xml",
                "application/json", "application/problem+json", "foo"));
        values.add(null);
        return values;
    }

    private HttpResponse<String> callAccepting(String path, String accept) throws Exception {
        return accept == null ? call("GET", path) : call("GET", path, "Accept", accept);
    }

    @ParameterizedTest
    @MethodSource("acceptValues")
    void shouldRedirectWhateverTheAcceptHeaderSays(String accept) throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/accept");

        HttpResponse<String> response = callAccepting("/" + ACTIVE_CODE, accept);

        assertRedirectsTo(response, "https://example.com/accept");
        assertThat(response.headers().allValues("Cache-Control")).containsExactly(NO_STORE);
        assertThat(response.headers().allValues("Content-Type")).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("acceptValues")
    void shouldAnswer404NeverA406ForAnUnknownCodeWhateverTheAcceptHeaderSays(String accept) throws Exception {
        HttpResponse<String> response = callAccepting("/Nothing1", accept);

        assertThat(response.statusCode()).isEqualTo(404);
        if ("foo".equals(accept)) {
            // Recorded: an unparseable Accept gets 404 with an empty body (D70 deviation), never 406.
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().allValues("Content-Type")).isEmpty();
        } else {
            assertNotFound(response, "/Nothing1");
        }
    }

    // ---- AC8 / D79: query string ----

    @Test
    void shouldNotForwardTheShortLinkQueryToTheTarget() throws Exception {
        String stored = "https://example.com/target?keep=1#frag";
        String code = create(stored);

        for (String query : List.of("?x=1", "?", "?x=1&keep=2", "?url=https://evil.example")) {
            HttpResponse<String> response = call("GET", "/" + code + query);

            assertRedirectsTo(response, stored);
        }
        assertRedirectsTo(call("HEAD", "/" + code + "?x=1"), stored);
    }

    @Test
    void shouldStillAnswer404WithTheSameBodyForAnUnknownCodeWithAQuery() throws Exception {
        HttpResponse<String> response = call("GET", "/Nothing1?x=1");

        assertNotFound(response, "/Nothing1");
    }

    // ---- Identity (D55) ----

    @Test
    void shouldRedirectAnonymousAndAnyAuthenticatedCallerRegardlessOfTheOwner() throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/bobs", TestUsers.BOB);

        assertRedirectsTo(call("GET", "/" + ACTIVE_CODE), "https://example.com/bobs");
        assertRedirectsTo(callAs("GET", "/" + ACTIVE_CODE, TestUsers.ALICE), "https://example.com/bobs");
        assertRedirectsTo(callAs("HEAD", "/" + ACTIVE_CODE, TestUsers.ALICE), "https://example.com/bobs");
        assertRedirectsTo(callAs("GET", "/" + ACTIVE_CODE, TestUsers.ADMIN), "https://example.com/bobs");
        assertNotFound(callAs("GET", "/Nothing1", TestUsers.ALICE), "/Nothing1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void shouldReturn401ForInvalidBasicCredentialsOnAnActiveAndAnUnknownCode(String method) throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/bad-credentials");
        // Positive control on the same path: without credentials the same request redirects.
        assertRedirectsTo(call(method, "/" + ACTIVE_CODE), "https://example.com/bad-credentials");

        for (String path : List.of("/" + ACTIVE_CODE, "/Nothing1")) {
            for (String header : List.of(ApiClient.rawBasicHeader(TestUsers.ALICE, "not-the-password"),
                    ApiClient.rawBasicHeader("nobody", "not-the-password"))) {
                HttpResponse<String> response = call(method, path, "Authorization", header);

                assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(401);
                assertThat(response.headers().firstValue("Location")).isEmpty();
                assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                        challenge -> assertThat(challenge).startsWith("Basic"));
                if ("GET".equals(method)) {
                    assertThat(json(response).path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
                }
            }
        }
    }

    // ---- Reads write nothing ----

    @Test
    void shouldWriteNothingOnGetOrHead() throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/readonly");
        Map<String, Object> before = data.rowState(ACTIVE_CODE);
        List<Integer> statuses = new ArrayList<>();

        for (String method : List.of("GET", "HEAD", "GET", "HEAD")) {
            statuses.add(call(method, "/" + ACTIVE_CODE).statusCode());
        }

        // Non-vacuity: every request reached the redirect (302), so the handler really ran.
        assertThat(statuses).containsExactly(302, 302, 302, 302);
        assertThat(data.rowState(ACTIVE_CODE)).isEqualTo(before);
        // Positive control: the same comparison does detect a write.
        data.seedClicks(ACTIVE_CODE, 1, Instant.parse("2026-03-01T10:15:30Z"));
        assertThat(data.rowState(ACTIVE_CODE)).isNotEqualTo(before);
    }

    // ---- AC5, D78: routing precedence ----

    @Test
    void shouldRouteInfrastructurePathsToTheirOwnHandlersForAnonymousAndAuthenticatedCallers() throws Exception {
        for (String user : new String[] {null, TestUsers.ALICE}) {
            HttpResponse<String> error = callAs("GET", "/error", user);
            assertThat(error.statusCode()).as("GET /error as %s", user).isEqualTo(500);
            assertThat(json(error).has("errorCode")).isFalse();

            HttpResponse<String> welcome = callAs("GET", "/swagger-ui.html", user);
            assertThat(welcome.statusCode()).isEqualTo(302);
            assertThat(welcome.headers().firstValue("Location")).contains("/swagger-ui/index.html");

            for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html",
                    "/actuator/health")) {
                assertThat(callAs("GET", path, user).statusCode()).as("GET %s as %s", path, user).isEqualTo(200);
            }
        }
    }

    @Test
    void shouldGive404ShortUrlNotFoundForSingleSegmentPathsThatOnlyLookLikeInfrastructure() throws Exception {
        // These fall to the redirect mapping (rule 7) and are malformed or unknown: never a 302, never a 5xx.
        for (String user : new String[] {null, TestUsers.ALICE}) {
            for (String path : List.of("/favicon.ico", "/v3", "/ab", "/a_b", "/API", "/Actuator")) {
                HttpResponse<String> response = callAs("GET", path, user);

                assertNotFound(response, path);
            }
        }
    }

    @Test
    void shouldRefuseAnonymousReservedPrefixesAndSendAuthenticatedApiToTheRedirectNotFound() throws Exception {
        HttpResponse<String> anonymousApi = call("GET", "/api");
        assertThat(anonymousApi.statusCode()).isEqualTo(401);
        assertThat(json(anonymousApi).path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        HttpResponse<String> anonymousActuator = call("GET", "/actuator");
        assertThat(anonymousActuator.statusCode()).isEqualTo(401);
        assertThat(json(anonymousActuator).path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

        // D78: an authenticated bare /api reaches the redirect mapping and is a 404, never a 302.
        assertNotFound(callAs("GET", "/api", TestUsers.ALICE), "/api");

        // Recorded: an authenticated /actuator is served by Actuator's own links mapping (200), never the redirect.
        HttpResponse<String> authenticatedActuator = callAs("GET", "/actuator", TestUsers.ALICE);
        assertThat(authenticatedActuator.statusCode()).isEqualTo(200);
        assertThat(authenticatedActuator.headers().firstValue("Location")).isEmpty();
        assertThat(ApiClient.contentType(authenticatedActuator)).contains("actuator");
    }

    @Test
    void shouldAnswerTheRootPathWithTheGenericNotFoundNeverARedirect() throws Exception {
        for (String user : new String[] {null, TestUsers.ALICE}) {
            HttpResponse<String> response = callAs("GET", "/", user);

            // Recorded: "{code}" needs at least one character, so no handler matches "/".
            assertThat(response.statusCode()).as("GET / as %s", user).isEqualTo(404);
            assertThat(json(response).path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
    }

    /** Recorded HEAD statuses (anonymous, alice): single-segment paths follow GET, deeper paths are D57 denyAll. */
    @Test
    void shouldGiveHeadTheRecordedStatusOnEveryRoutingPath() throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/routing-control");
        assertRedirectsTo(call("HEAD", "/" + ACTIVE_CODE), "https://example.com/routing-control");
        Map<String, int[]> expected = new TreeMap<>();
        expected.put("/error", new int[] {500, 500});
        expected.put("/swagger-ui.html", new int[] {302, 302});
        expected.put("/api", new int[] {401, 404});
        expected.put("/favicon.ico", new int[] {404, 404});
        expected.put("/v3", new int[] {404, 404});
        expected.put("/ab", new int[] {404, 404});
        expected.put("/a_b", new int[] {404, 404});
        expected.put("/", new int[] {404, 404});
        // Two or more segments: HEAD is not admitted by any permit rule for the docs, so denyAll applies.
        expected.put("/v3/api-docs", new int[] {401, 403});
        expected.put("/v3/api-docs/swagger-config", new int[] {401, 403});
        expected.put("/swagger-ui/index.html", new int[] {401, 403});
        expected.put("/actuator/health", new int[] {401, 200});
        expected.put("/actuator", new int[] {401, 200});

        for (Map.Entry<String, int[]> entry : expected.entrySet()) {
            String path = entry.getKey();
            int[] statuses = entry.getValue();
            HttpResponse<String> anonymous = call("HEAD", path);
            HttpResponse<String> alice = callAs("HEAD", path, TestUsers.ALICE);

            assertThat(anonymous.statusCode()).as("anonymous HEAD %s", path).isEqualTo(statuses[0]);
            assertThat(alice.statusCode()).as("alice HEAD %s", path).isEqualTo(statuses[1]);
            assertThat(anonymous.body()).isEmpty();
            // Every 404 row also names its cause through GET on the same path (a HEAD has no body).
            if (statuses[1] == 404) {
                String errorCode = "/".equals(path) ? "RESOURCE_NOT_FOUND" : NOT_FOUND;
                HttpResponse<String> get = callAs("GET", path, TestUsers.ALICE);
                assertThat(get.statusCode()).as("alice GET %s", path).isEqualTo(404);
                assertThat(json(get).path("errorCode").asText()).as("errorCode of GET %s", path)
                        .isEqualTo(errorCode);
            }
            if (statuses[0] == 404) {
                String errorCode = "/".equals(path) ? "RESOURCE_NOT_FOUND" : NOT_FOUND;
                HttpResponse<String> get = call("GET", path);
                assertThat(get.statusCode()).as("anonymous GET %s", path).isEqualTo(404);
                assertThat(json(get).path("errorCode").asText()).as("errorCode of GET %s", path)
                        .isEqualTo(errorCode);
            }
        }
    }

    @Test
    void shouldNeverRedirectAReservedPrefixRowForAnonymousCallersEvenIfARawSqlRowExists() throws Exception {
        data.seed("api", "ACTIVE", "https://example.com/shadow-api");
        data.seed("actuator", "ACTIVE", "https://example.com/shadow-actuator");
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/control");
        assertRedirectsTo(call("GET", "/" + ACTIVE_CODE), "https://example.com/control");

        for (String method : List.of("GET", "HEAD")) {
            assertThat(call(method, "/api").statusCode()).as("%s /api", method).isEqualTo(401);
            assertThat(call(method, "/actuator").statusCode()).as("%s /actuator", method).isEqualTo(401);
        }
    }

    @Test
    void shouldStillServeTheManagementApiToItsOwner() throws Exception {
        String code = create("https://example.com/mine");

        HttpResponse<String> details = callAs("GET", "/api/v1/urls/" + code, TestUsers.ALICE);

        assertThat(details.statusCode()).isEqualTo(200);
        assertThat(ApiClient.contentType(details)).startsWith("application/json");
        assertThat(json(details).path("shortCode").asText()).isEqualTo(code);
    }

    @Test
    void shouldHaveExactlyTheExpectedSingleSegmentGetMappings() {
        Set<String> singleSegment = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            boolean getCapable = methods.isEmpty() || methods.contains(RequestMethod.GET);
            if (!getCapable || info.getPathPatternsCondition() == null) {
                continue;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                String trimmed = pattern.startsWith("/") ? pattern.substring(1) : pattern;
                if (!trimmed.contains("/")) {
                    singleSegment.add(pattern);
                }
            }
        }

        assertThat(singleSegment).containsExactlyInAnyOrder("/{code}", "/error", "/swagger-ui.html");
    }

    // ---- D80: trailing slash ----

    @Test
    void shouldRecordTheTrailingSlashBehaviourAs401AnonymousAnd403Authenticated() throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/slash");
        assertRedirectsTo(call("GET", "/" + ACTIVE_CODE), "https://example.com/slash");

        for (String method : List.of("GET", "HEAD")) {
            HttpResponse<String> anonymous = call(method, "/" + ACTIVE_CODE + "/");
            assertThat(anonymous.statusCode()).as("anonymous %s", method).isEqualTo(401);
            assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                    challenge -> assertThat(challenge).startsWith("Basic"));
            assertThat(anonymous.headers().firstValue("Location")).isEmpty();

            HttpResponse<String> authenticated = callAs(method, "/" + ACTIVE_CODE + "/", TestUsers.ALICE);
            assertThat(authenticated.statusCode()).as("authenticated %s", method).isEqualTo(403);
            assertThat(authenticated.headers().firstValue("Location")).isEmpty();
        }
    }

    // ---- Hostile paths are never a 500 ----

    @ParameterizedTest
    @ValueSource(strings = {"/abc{def}", "/abc|def", "/abc\"def", "/abc<def>"})
    void shouldRejectRawIllegalCharactersInThePathWith400(String target) throws Exception {
        assertThat(rawStatus(target)).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abc%2F", "/abc%25", "/abc%5C", "/abc;x=1", "/abc%0a", "/abc%0d%0a", "/abc%00"})
    void shouldRejectFirewallForbiddenPathsWith400AndNeverA302OrA500(String path) throws Exception {
        data.seed(ACTIVE_CODE, "ACTIVE", "https://example.com/control");
        assertRedirectsTo(call("GET", "/" + ACTIVE_CODE), "https://example.com/control");

        HttpResponse<String> response = call("GET", path);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void shouldNotAnswer500ForAnInvalidUtf8EscapeInThePath() throws Exception {
        HttpResponse<String> response = call("GET", "/%C3");

        // Observed: Tomcat rejects the invalid UTF-8 escape itself with 400, before any Spring handler, so
        // there is no ProblemDetail and no errorCode to assert.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }
}
