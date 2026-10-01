package com.schwab.urlshortener.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal real-HTTP client for the management API, shared by {@code *IT} and Cucumber steps.
 * Credentials are resolved from {@link TestUsers} by username and are never printed or logged.
 * Authentication is per call: pass {@code null} for an anonymous request.
 */
public final class ApiClient {

    public static final String CREATE_PATH = "/api/v1/urls";

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> FRAMING_AND_DATE_HEADERS =
            Set.of("date", "content-length", "transfer-encoding", "connection", "x-request-id");

    /**
     * Headers that differ on every response by design: the date and the request ID (US-014 AC1). Header-equality
     * comparisons ignore them; tests of the request ID itself assert it separately.
     */
    public static final Set<String> PER_REQUEST_HEADERS = Set.of("date", "x-request-id");

    private final int port;
    private final ObjectMapper mapper;

    public ApiClient(int port, ObjectMapper mapper) {
        this.port = port;
        this.mapper = mapper;
    }

    /** JSON create body. A null {@code alias} omits the field. */
    public String createBody(String originalUrl, String alias) {
        ObjectNode node = mapper.createObjectNode();
        node.put("originalUrl", originalUrl);
        if (alias != null) {
            node.put("alias", alias);
        }
        return node.toString();
    }

    /** POST /api/v1/urls with a JSON content type. {@code headers} are name/value pairs. */
    public HttpResponse<String> post(String user, String body, String... headers) throws IOException, InterruptedException {
        return HTTP.send(request("POST", CREATE_PATH, user, "application/json", body, headers).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public CompletableFuture<HttpResponse<String>> postAsync(String user, String body, String... headers) {
        return HTTP.sendAsync(request("POST", CREATE_PATH, user, "application/json", body, headers).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Any method and path; {@code contentType} and {@code body} may be null. */
    public HttpResponse<String> send(String method, String path, String user, String contentType, String body,
            String... headers) throws IOException, InterruptedException {
        return HTTP.send(request(method, path, user, contentType, body, headers).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null, null);
    }

    public JsonNode json(HttpResponse<String> response) throws IOException {
        return mapper.readTree(response.body());
    }

    public static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private HttpRequest.Builder request(String method, String path, String user, String contentType, String body,
            String... headers) {
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("headers must be name/value pairs");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (user != null) {
            builder.header("Authorization", basicHeader(user, user));
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return builder;
    }

    /**
     * The Basic Authorization header for {@code login} (as typed, for example a different letter case) with
     * the password of the account {@code user}, which must be a known test user.
     */
    public static String basicHeader(String login, String user) {
        return rawBasicHeader(login, passwordOf(user));
    }

    /** The Basic Authorization header for exactly this login and password, whatever they are. */
    public static String rawBasicHeader(String login, String password) {
        String token = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    static String passwordOf(String user) {
        return switch (TestUsers.require(user)) {
            case TestUsers.ALICE -> TestUsers.ALICE_PASSWORD;
            case TestUsers.BOB -> TestUsers.BOB_PASSWORD;
            case TestUsers.ADMIN -> TestUsers.ADMIN_PASSWORD;
            default -> throw new IllegalStateException("TestUsers.require admitted a user with no password");
        };
    }

    /** Names of the keys of a JSON object, for exact key-set assertions. */
    public static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** Headers except Date and Content-Length (HEAD carries none), keyed case-insensitively. */
    public static Map<String, List<String>> stableHeaders(HttpResponse<String> response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!PER_REQUEST_HEADERS.contains(lower) && !"content-length".equals(lower)) {
                headers.put(name, values);
            }
        });
        return headers;
    }

    /**
     * Headers that describe the representation, keyed case-insensitively: everything except Date and the
     * message-framing headers (Content-Length, Transfer-Encoding, Connection). A HEAD response has no body to
     * frame, so it legitimately lacks the chunked framing of the matching GET, and Tomcat adds Connection: close
     * to some 4xx GET responses only.
     */
    public static Map<String, List<String>> headersExceptFraming(HttpResponse<String> response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((name, values) -> {
            if (!FRAMING_AND_DATE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, values);
            }
        });
        return headers;
    }
}
