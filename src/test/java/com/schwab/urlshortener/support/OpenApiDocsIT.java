package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /v3/api-docs} returns 200 with a valid OpenAPI document (US-001 AC5), and (US-006
 * AC13) documents {@code POST /api/v1/urls}: request and response schemas, Basic authentication
 * applied per controller, 406 (D70), problem+json error responses and the punycode rule (D49).
 */
class OpenApiDocsIT extends IntegrationTestBase {

    private static final String CREATE_PATH = "/api/v1/urls";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    private JsonNode docs;
    private JsonNode post;

    @BeforeEach
    void loadDocs() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        docs = objectMapper.readTree(response.getBody());
        post = docs.path("paths").path(CREATE_PATH).path("post");
    }

    /** Follows a local {@code #/components/schemas/X} reference; returns the node itself otherwise. */
    private JsonNode resolve(JsonNode schema) {
        JsonNode ref = schema.get("$ref");
        if (ref == null) {
            return schema;
        }
        String[] parts = ref.asText().substring(2).split("/");
        JsonNode node = docs;
        for (String part : parts) {
            node = node.path(part);
        }
        return node;
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void shouldReturnValidOpenApiDocument() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.has("openapi")).isTrue();
        assertThat(body.get("openapi").asText()).startsWith("3.");
        assertThat(body.has("info")).isTrue();
    }

    @Test
    void shouldDescribeCreateRequestBodyWithOriginalUrlRequiredAndAliasOptional() {
        assertThat(post.isMissingNode()).as("POST %s documented", CREATE_PATH).isFalse();
        JsonNode content = post.path("requestBody").path("content");
        assertThat(names(content)).containsExactly("application/json");
        JsonNode schema = resolve(content.path("application/json").path("schema"));
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("originalUrl", "alias");
        List<String> required = new java.util.ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).contains("originalUrl").doesNotContain("alias");
    }

    @Test
    void shouldDescribe201AsJsonOnlyWithTheEightFieldResourceAndLocationHeader() {
        JsonNode created = post.path("responses").path("201");
        assertThat(created.isMissingNode()).isFalse();
        assertThat(names(created.path("content"))).containsExactly("application/json");
        JsonNode schema = resolve(created.path("content").path("application/json").path("schema"));
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("shortCode", "shortUrl",
                "originalUrl", "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt");
        assertThat(created.path("headers").has("Location")).isTrue();
    }

    @Test
    void shouldDescribeEveryErrorResponseAsProblemJsonOnlyWithTheProblemSchema() {
        for (String status : List.of("400", "401", "406", "409", "415", "503")) {
            JsonNode response = post.path("responses").path(status);
            assertThat(response.isMissingNode()).as("response %s documented", status).isFalse();
            assertThat(names(response.path("content"))).as("media types of %s", status)
                    .containsExactly("application/problem+json");
            JsonNode schema = response.path("content").path("application/problem+json").path("schema");
            assertThat(schema.path("$ref").asText()).as("schema of %s", status).endsWith("/Problem");
        }
    }

    @Test
    void shouldDocumentProblemSchemaWithTheSamePropertiesAsARealErrorBody() throws Exception {
        ApiClient api = new ApiClient(port, objectMapper);
        HttpResponse<String> real = api.post(TestUsers.ALICE, api.createBody("https://example.com/docs", "ab"));
        assertThat(real.statusCode()).isEqualTo(400);
        assertThat(api.json(real).path("errorCode").asText()).isEqualTo("INVALID_ALIAS");

        JsonNode problem = docs.path("components").path("schemas").path("Problem");

        assertThat(names(problem.path("properties"))).isEqualTo(ApiClient.keys(api.json(real)));
    }

    @Test
    void shouldApplyBasicAuthenticationToTheOperationOnly() {
        JsonNode scheme = docs.path("components").path("securitySchemes").path("basicAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("basic");
        assertThat(post.path("security").toString()).contains("basicAuth");
        assertThat(docs.has("security")).as("no global security requirement").isFalse();
    }

    @Test
    void shouldNotDocumentParametersOnCreateBecauseTheAuthenticationArgumentIsHidden() {
        assertThat(post.path("parameters").isMissingNode() || post.path("parameters").isEmpty()).isTrue();
    }

    @Test
    void shouldStatePunycodeRuleForInternationalisedHostsInTheOperationDescription() {
        assertThat(post.path("description").asText()).containsIgnoringCase("punycode");
    }

    @Test
    void shouldStayPublicWithoutCredentials() {
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
