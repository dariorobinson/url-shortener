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
import java.util.Locale;
import java.util.Set;
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
            String token = Base64.getEncoder()
                    .encodeToString((user + ":" + passwordOf(user)).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return builder;
    }

    static String passwordOf(String user) {
        return switch (user.toLowerCase(Locale.ROOT)) {
            case TestUsers.ALICE -> TestUsers.ALICE_PASSWORD;
            case TestUsers.BOB -> TestUsers.BOB_PASSWORD;
            case TestUsers.ADMIN -> TestUsers.ADMIN_PASSWORD;
            default -> throw new IllegalArgumentException("unknown test user");
        };
    }

    /** Names of the keys of a JSON object, for exact key-set assertions. */
    public static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
