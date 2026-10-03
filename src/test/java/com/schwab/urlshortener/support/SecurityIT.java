package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-005 security behaviour through a real Tomcat: what MockMvc cannot show (error rendering through the
 * real container, real URL decoding, real container header handling). Uses the JDK HTTP client so raw paths reach the
 * server unmodified. Credentials come from {@link TestUsers} and are never printed or put in
 * assertion messages.
 */
class SecurityIT extends IntegrationTestBase {

    private static final String CHALLENGE = "Basic realm=\"url-shortener\"";
    private static final Set<String> PROBLEM_KEYS =
            Set.of("type", "title", "status", "detail", "instance", "errorCode");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<HttpResponse<String>> allResponses = new ArrayList<>();
    private ShortUrlTestData data;

    @BeforeEach
    void truncate() {
        data = new ShortUrlTestData(jdbc);
        data.truncate();
    }

    private HttpResponse<String> send(String method, String path, String user, String password) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (user != null) {
            builder.header("Authorization", ApiClient.rawBasicHeader(user, password));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        allResponses.add(response);
        // AC7: no session or cookie on any response, whatever its status.
        assertThat(response.headers().firstValue("Set-Cookie")).as("Set-Cookie on %s %s", method, path).isEmpty();
        return response;
    }

    private HttpResponse<String> send(String method, String path, String user, String password, String jsonBody)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", ApiClient.rawBasicHeader(user, password))
                .method(method, HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        allResponses.add(response);
        return response;
    }

    private HttpResponse<String> anonymous(String method, String path) throws Exception {
        return send(method, path, null, null);
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return objectMapper.readTree(response.body());
    }

    private static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    // ---- AC1 / AC8 shape ----

    @Test
    void shouldReturn401ProblemDetailWithBasicChallengeWhenAnonymousCallsApi() throws Exception {
        HttpResponse<String> response = anonymous("GET", "/api/v1/urls");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(CHALLENGE);
        assertThat(contentType(response)).startsWith("application/problem+json");
        JsonNode body = json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        assertThat(body.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/urls");
        assertThat(body.get("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void shouldNotIncludeQueryStringInProblemInstance() throws Exception {
        HttpResponse<String> response = anonymous("GET", "/api/v1/urls?token=secret-marker");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("secret-marker");
        assertThat(json(response).get("instance").asText()).isEqualTo("/api/v1/urls");
    }

    @Test
    void shouldReturnIdentical401BodyForWrongPasswordUnknownUserAndNoCredentials() throws Exception {
        HttpResponse<String> none = anonymous("GET", "/api/v1/urls");
        HttpResponse<String> wrongPassword = send("GET", "/api/v1/urls", TestUsers.ALICE,
                TestUsers.ALICE_PASSWORD + "-wrong");
        HttpResponse<String> unknownUser = send("GET", "/api/v1/urls", "nobody", "whatever-password");

        for (HttpResponse<String> response : List.of(wrongPassword, unknownUser)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly(CHALLENGE);
            assertThat(contentType(response)).startsWith("application/problem+json");
            assertThat(response.body()).isEqualTo(none.body());
        }
        assertThat(unknownUser.body()).isEqualTo(wrongPassword.body());
        assertThat(wrongPassword.body()).doesNotContain("nobody").doesNotContain(TestUsers.ALICE);
    }

    @Test
    void shouldReturn401ForMalformedAuthorizationHeaderWithSameBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls"))
                .header("Authorization", "Basic !!!not-base64!!!").GET().build();
        HttpResponse<String> malformed = client.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> none = anonymous("GET", "/api/v1/urls");

        assertThat(malformed.statusCode()).isEqualTo(401);
        assertThat(malformed.body()).isEqualTo(none.body());
        assertThat(malformed.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void shouldReturn401ForInvalidCredentialsEvenOnPublicPath() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/health", TestUsers.ALICE,
                TestUsers.ALICE_PASSWORD + "-wrong");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    // ---- AC2 / AC3 over real HTTP (no business endpoint yet: 404 proves security passed) ----

    @Test
    void shouldPassSecurityForUserAndAdminOnApiPaths() throws Exception {
        HttpResponse<String> user = send("GET", "/api/v1/does-not-exist", TestUsers.ALICE, TestUsers.ALICE_PASSWORD);
        HttpResponse<String> admin = send("GET", "/api/v1/does-not-exist", TestUsers.ADMIN, TestUsers.ADMIN_PASSWORD);

        assertThat(user.statusCode()).isEqualTo(404);
        assertThat(admin.statusCode()).isEqualTo(404);
    }

    @Test
    void shouldAuthenticateUsernameCaseInsensitively() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/does-not-exist", "ALICE", TestUsers.ALICE_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void shouldReturn404NotUnauthorizedForAuthenticatedCallerOnMissingResource() throws Exception {
        // An authenticated caller gets 404, not 401. This does not isolate the ERROR-dispatch permit
        // rule: GET /error is a single public segment, and the security context is restored on the
        // error dispatch for other methods, so this passes with or without that rule.
        HttpResponse<String> response = send("GET", "/api/v1/does-not-exist", TestUsers.ALICE,
                TestUsers.ALICE_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.body()).doesNotContain("AUTHENTICATION_REQUIRED");
    }

    @Test
    void shouldReturn404ForAuthenticatedCallerOnUnexposedActuatorEndpoint() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/env", TestUsers.ADMIN, TestUsers.ADMIN_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ---- AC4 ----

    @Test
    void shouldReturn403AccessDeniedWhenUserDeletes() throws Exception {
        HttpResponse<String> response = send("DELETE", "/api/v1/urls/abc1234", TestUsers.ALICE,
                TestUsers.ALICE_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(contentType(response)).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        JsonNode body = json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        assertThat(body.get("title").asText()).isEqualTo("Forbidden");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/urls/abc1234");
        assertThat(body.get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void shouldNotReturn403WhenAdminDeletes() throws Exception {
        HttpResponse<String> response = send("DELETE", "/api/v1/urls/abc1234", TestUsers.ADMIN,
                TestUsers.ADMIN_PASSWORD);

        // Security passed and the delete handler ran: an unknown code is the handler's own 404, not the
        // unmapped-path RESOURCE_NOT_FOUND (US-009).
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("SHORT_URL_NOT_FOUND");
    }

    @Test
    void shouldReturn401ForAnonymousDelete() throws Exception {
        HttpResponse<String> response = anonymous("DELETE", "/api/v1/urls/abc1234");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    // ---- AC5 ----

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void shouldNotBlockShortCodePathsForAnonymousGetAndHead(String method) throws Exception {
        // Public redirect (US-008): a code with no row, and malformed codes, are all the redirect's own 404
        // (SHORT_URL_NOT_FOUND, D72), not a security error. HEAD has no body, so only GET shows the errorCode.
        // Same-path control (after the 404s, so no mid-test truncation): once "abc1234" exists, the same
        // request redirects, so the 404 above was about the missing row.
        for (String path : List.of("/abc1234", "/ab", "/a_b", "/v3")) {
            HttpResponse<String> response = anonymous(method, path);

            assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(404);
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
            assertThat(response.body()).doesNotContain("AUTHENTICATION_REQUIRED").doesNotContain("ACCESS_DENIED");
            if ("GET".equals(method)) {
                assertThat(json(response).get("errorCode").asText()).as("GET %s", path)
                        .isEqualTo("SHORT_URL_NOT_FOUND");
            } else {
                assertThat(response.body()).as("HEAD %s has no body", path).isEmpty();
            }
        }

        data.seed("abc1234", "ACTIVE", "https://example.com/control");
        HttpResponse<String> control = anonymous(method, "/abc1234");
        assertThat(control.statusCode()).as("%s /abc1234 control", method).isEqualTo(302);
    }

    @Test
    void shouldRequireAuthenticationForNonRedirectMethodsOnSingleSegment() throws Exception {
        for (String method : List.of("POST", "PUT", "DELETE")) {
            assertThat(anonymous(method, "/abc1234").statusCode()).as(method).isEqualTo(401);
        }
        assertThat(anonymous("GET", "/a/b").statusCode()).isEqualTo(401);
    }

    @Test
    void shouldRequireAuthenticationForApiAndActuatorPrefixes() throws Exception {
        for (String path : List.of("/api", "/actuator", "/actuator/env")) {
            HttpResponse<String> response = anonymous("GET", path);

            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(json(response).get("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void shouldServeHealthAnonymously() throws Exception {
        HttpResponse<String> response = anonymous("GET", "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void shouldNotExposeAnyApiDocumentationPaths() throws Exception {
        // Two or more segments fall to denyAll (D57), so an anonymous caller is asked to authenticate.
        for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml",
                "/swagger-ui/index.html")) {
            HttpResponse<String> response = anonymous("GET", path);

            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(json(response).get("errorCode").asText()).as(path).isEqualTo("AUTHENTICATION_REQUIRED");
        }
        // One segment is only ever a short code: malformed, so the generic not-found (D72).
        HttpResponse<String> welcome = anonymous("GET", "/swagger-ui.html");
        assertThat(welcome.statusCode()).isEqualTo(404);
        assertThat(json(welcome).get("errorCode").asText()).isEqualTo("SHORT_URL_NOT_FOUND");
    }

    // ---- Real URL decoding / firewall (bypass pins) ----

    @ParameterizedTest
    @ValueSource(strings = {
            "/%61pi/v1/urls",
            "/api/v1/urls/",
            "/API/v1/urls",
            "//api/v1/urls",
            "/api;x=1/v1/urls",
            "/api/v1/urls/%2e%2e/x",
            "/api/v1/urls/%2e%2e",
            "/api/v1/%2Furls",
            "/api%2Fv1/urls",
            "/act%75ator/env",
            "/actuator;x=1/env",
            "//actuator/env",
            "/ACTUATOR/env",
            "/actuator/health/../env",
            "/actuator/env/",
            "/api/../api/v1/urls"})
    void shouldNeverLetEncodedOrAlteredApiAndActuatorPathsPassSecurityAnonymously(String path) throws Exception {
        HttpResponse<String> response = anonymous("GET", path);

        // 401 (security stopped it) or 400 (container/firewall rejected it). A 2xx or 404 would mean
        // the request passed security or was routed as a public single segment.
        assertThat(response.statusCode()).as("GET %s", path).isIn(400, 401);
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/urls/abc/,403",
            // The case variant matches no explicit rule (the matchers are case-sensitive, D3), so the
            // final anyRequest().denyAll() refuses it with 403 ACCESS_DENIED (D57).
            "/API/v1/urls/abc,403",
            "/api/v1/urls/abc;x=1,400",
            "/api/v1//urls/abc,400",
            "/api/v1/urls/%2e%2e,400",
            "/%61pi/v1/urls/abc,403",
            "/api/v1/urls/abc%2F,400",
            "/api/v1/urls/%0d%0aX,400",
            "/api/v1/urls/%0aX,400"})
    void shouldNotLetUserReachDeleteHandlerThroughPathVariants(String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = send("DELETE", path, TestUsers.ALICE, TestUsers.ALICE_PASSWORD);

        // Exact status with the US-009 delete handler mapped. As a USER every row is refused before any handler
        // could run, so adding the handler changed none of them.
        // 403: the ADMIN rule (D3) or the final denyAll (D57) refused the USER; 400: the firewall rejected the path.
        assertThat(response.statusCode()).as("DELETE %s", path).isEqualTo(expectedStatus);
        if (expectedStatus == 403) {
            // A 403 here is the security ACCESS_DENIED problem, not some other 403.
            assertThat(contentType(response)).startsWith("application/problem+json");
            assertThat(json(response).get("errorCode").asText()).as("DELETE %s", path).isEqualTo("ACCESS_DENIED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/urls/%0d%0aX", "/api/v1/urls/%0aX", "/api/v1/urls/%0dX"})
    void shouldRejectEncodedLineBreaksInApiPathsBeforeRouting(String path) throws Exception {
        HttpResponse<String> anonymous = anonymous("GET", path);
        HttpResponse<String> user = send("GET", path, TestUsers.ALICE, TestUsers.ALICE_PASSWORD);

        assertThat(anonymous.statusCode()).as("anonymous GET %s", path).isEqualTo(400);
        assertThat(user.statusCode()).as("user GET %s", path).isEqualTo(400);
    }

    @Test
    void shouldReturn403AccessDeniedForPostToUpperCaseApiPathAndCreateNothing() throws Exception {
        String url = "https://example.com/upper-case/" + UUID.randomUUID();
        String body = "{\"originalUrl\":\"" + url + "\"}";

        HttpResponse<String> response = send("POST", "/API/v1/urls", TestUsers.ALICE, TestUsers.ALICE_PASSWORD, body);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(contentType(response)).startsWith("application/problem+json");
        assertThat(json(response).get("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    @Test
    void shouldReturn404ForPostToTrailingSlashApiPathAndCreateNothing() throws Exception {
        String url = "https://example.com/trailing-slash/" + UUID.randomUUID();
        String body = "{\"originalUrl\":\"" + url + "\"}";

        HttpResponse<String> response = send("POST", "/api/v1/urls/", TestUsers.ALICE, TestUsers.ALICE_PASSWORD, body);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    // ---- AC7 ----

    @Test
    void shouldSetNoCookieOnAnyResponse() throws Exception {
        anonymous("GET", "/api/v1/urls");
        send("GET", "/api/v1/does-not-exist", TestUsers.ALICE, TestUsers.ALICE_PASSWORD);
        send("DELETE", "/api/v1/urls/abc1234", TestUsers.ALICE, TestUsers.ALICE_PASSWORD);
        anonymous("GET", "/actuator/health");
        anonymous("GET", "/abc1234");
        data.seed("Cookie01", "ACTIVE", "https://example.com/cookie");
        HttpResponse<String> redirect = anonymous("GET", "/Cookie01");

        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(allResponses).hasSize(6);
        assertThat(allResponses).allSatisfy(response ->
                assertThat(response.headers().map()).doesNotContainKey("set-cookie"));
    }

    @Test
    void shouldAcceptAuthenticatedPostWithoutCsrfToken() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/does-not-exist", TestUsers.ALICE,
                TestUsers.ALICE_PASSWORD);

        // CSRF disabled: not 403 ACCESS_DENIED. No POST handler for this path: 404 or 405.
        assertThat(response.statusCode()).isIn(404, 405);
    }
}
