package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-014 through a real Tomcat: request IDs (AC1), security headers (AC2), HSTS from the trusted proxy (AC6), the
 * actuator lock-down and HEAD probes (H6, H7), the body limit (H10), Tomcat's own error page (H11), the pool timeout
 * (H12) and the lost-click metric (H13). The untrusted-proxy half of AC6 is {@link UntrustedProxyHstsIT}.
 */
@ExtendWith(OutputCaptureExtension.class)
class HardeningIT extends IntegrationTestBase {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        data.dropClickFailures();
    }

    @AfterEach
    void tearDown() {
        data.dropClickFailures();
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        return api.send("GET", path, null, null, null, headers);
    }

    // ---- AC1: request ID

    @Test
    void shouldReturnAGeneratedRequestIdOnEveryKindOfResponseAndADifferentOneEachTime() throws Exception {
        data.seed("Hard001", "ACTIVE", "https://example.com/h");
        List<HttpResponse<String>> responses = List.of(get("/Hard001"), get("/actuator/health"),
                api.send("GET", "/api/v1/urls/Hard001", null, null, null), get("/Unknown1"));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(302, 200, 401, 404);
        assertThat(responses).allSatisfy(r -> assertThat(r.headers().firstValue("X-Request-Id")).hasValueSatisfying(
                id -> assertThat(id).matches(UUID_PATTERN)));
        assertThat(responses.stream().map(r -> r.headers().firstValue("X-Request-Id").orElseThrow()).distinct())
                .hasSize(4);
    }

    @Test
    void shouldEchoAPlainClientRequestIdAndReplaceAnUnsafeOne() throws Exception {
        assertThat(get("/actuator/health", "X-Request-Id", "trace-abc-123").headers().firstValue("X-Request-Id"))
                .contains("trace-abc-123");
        assertThat(get("/actuator/health", "X-Request-Id", "bad id").headers().firstValue("X-Request-Id"))
                .hasValueSatisfying(id -> assertThat(id).matches(UUID_PATTERN));
    }

    @Test
    void shouldPutTheRequestIdOnTheLogLinesOfThatRequest(CapturedOutput output) throws Exception {
        HttpResponse<String> created = api.post(TestUsers.ALICE, api.createBody("https://example.com/logged", null),
                "X-Request-Id", "log-proof-1");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(output.getAll()).containsPattern("requestId=log-proof-1\\].*Short URL created");
    }

    @Test
    void shouldIncludeTheRequestIdInA500ProblemAndItsResponseHeader(CapturedOutput output) throws Exception {
        data.failShortUrlInserts();

        HttpResponse<String> failed = api.post(TestUsers.ALICE, api.createBody("https://example.com/500", null),
                "X-Request-Id", "five-hundred-1");

        assertThat(failed.statusCode()).isEqualTo(500);
        JsonNode problem = api.json(failed);
        assertThat(problem.path("errorCode").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.path("requestId").asText()).isEqualTo("five-hundred-1");
        assertThat(failed.headers().firstValue("X-Request-Id")).contains("five-hundred-1");
        assertThat(failed.body()).doesNotContain("injected");
        // Positive control: the failure was logged with the same request ID.
        assertThat(output.getAll()).containsPattern("requestId=five-hundred-1\\].*Unhandled exception");
    }

    @Test
    void shouldKeepTheRequestIdOutOfEveryNon500Problem() throws Exception {
        HttpResponse<String> notFound = get("/Unknown1");

        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(api.json(notFound).has("requestId")).isFalse();
    }

    // ---- AC2: security headers

    @Test
    void shouldSendNosniffAndFrameDenyOnRedirectsSuccessesAndErrors() throws Exception {
        data.seed("Hard002", "ACTIVE", "https://example.com/h2");
        for (HttpResponse<String> response : List.of(get("/Hard002"), get("/actuator/health"), get("/Unknown1"),
                api.send("GET", "/api/v1/urls/x", null, null, null))) {
            assertThat(response.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
            assertThat(response.headers().allValues("X-Frame-Options")).containsExactly("DENY");
        }
    }

    // ---- AC6: HSTS from the trusted proxy (loopback is in Tomcat's default internal proxies)

    @Test
    void shouldSendHstsOnlyWhenTheTrustedProxyReportsHttps() throws Exception {
        HttpResponse<String> overHttps = get("/actuator/health", "X-Forwarded-Proto", "https");
        HttpResponse<String> plain = get("/actuator/health");

        assertThat(overHttps.headers().allValues("Strict-Transport-Security"))
                .containsExactly("max-age=31536000 ; includeSubDomains");
        assertThat(plain.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    // ---- H6, H7, H13: actuator

    @Test
    void shouldAnswerAnonymousHeadOnHealthWith200() throws Exception {
        HttpResponse<String> head = api.send("HEAD", "/actuator/health", null, null, null);

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
    }

    @Test
    void shouldKeepMetricsForAdminOnly() throws Exception {
        String path = "/actuator/metrics/shortener.clicks.lost";

        assertThat(api.send("GET", path, null, null, null).statusCode()).isEqualTo(401);
        HttpResponse<String> user = api.send("GET", path, TestUsers.ALICE, null, null);
        assertThat(user.statusCode()).isEqualTo(403);
        assertThat(api.json(user).path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(api.send("GET", path, TestUsers.ADMIN, null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void shouldCountAClickLostToFailOpenInTheMetric() throws Exception {
        data.seed("Lost001", "ACTIVE", "https://example.com/lost");
        double before = lostClicks();
        data.failClickInserts();

        assertThat(get("/Lost001").statusCode()).isEqualTo(302);

        assertThat(lostClicks()).isEqualTo(before + 1);
        // Positive control: once recording works again, a click is recorded and the metric does not move.
        data.dropClickFailures();
        assertThat(get("/Lost001").statusCode()).isEqualTo(302);
        assertThat(lostClicks()).isEqualTo(before + 1);
        assertThat(data.clickEventCount("Lost001")).isEqualTo(1);
    }

    private double lostClicks() throws Exception {
        HttpResponse<String> metric = api.send("GET", "/actuator/metrics/shortener.clicks.lost", TestUsers.ADMIN,
                null, null);
        assertThat(metric.statusCode()).isEqualTo(200);
        return api.json(metric).path("measurements").get(0).path("value").asDouble();
    }

    // ---- H10: body limit

    @Test
    void shouldRefuseAnOversizedDeclaredBodyWith413BeforeAuthenticationAndCreateNothing() throws Exception {
        String body = "{\"originalUrl\":\"https://example.com/" + "a".repeat(17_000) + "\"}";

        HttpResponse<String> anonymous = api.send("POST", "/api/v1/urls", null, "application/json", body);
        HttpResponse<String> alice = api.post(TestUsers.ALICE, body);

        for (HttpResponse<String> response : List.of(anonymous, alice)) {
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(api.json(response).path("errorCode").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        }
        assertThat(data.rowCount()).isZero();
    }

    @Test
    void shouldRefuseAnOversizedChunkedBodyWith413() throws Exception {
        byte[] body = ("{\"originalUrl\":\"https://example.com/" + "a".repeat(17_000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls"))
                .header("Content-Type", "application/json")
                .header("Authorization", ApiClient.basicHeader(TestUsers.ALICE, TestUsers.ALICE))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> (InputStream) new ByteArrayInputStream(body)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(data.rowCount()).isZero();
    }

    @Test
    void shouldStillAcceptANormalBodyUnderTheLimit() throws Exception {
        assertThat(api.post(TestUsers.ALICE, api.createBody("https://example.com/ok", null)).statusCode())
                .isEqualTo(201);
    }

    // ---- H11: Tomcat's own error page

    @Test
    void shouldNotRevealTheServerVersionOnTomcatsOwnErrorPage() throws Exception {
        String response;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /" + "a".repeat(9000) + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).doesNotContain("Apache Tomcat").doesNotContain("Exception").doesNotContain("at org.");
    }

    // ---- H12: pool timeout

    @Test
    void shouldFailFastWhenThePoolIsExhausted() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(3000L);
    }
}
