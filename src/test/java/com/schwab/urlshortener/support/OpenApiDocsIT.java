package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
 * applied per controller, 406 (D70), problem+json error responses and the punycode rule (D49). Also
 * documents {@code GET /api/v1/urls/{code}} (US-007): one path parameter, 200/401/404/406, and the D71 use.
 */
class OpenApiDocsIT extends IntegrationTestBase {

    private static final String CREATE_PATH = "/api/v1/urls";
    private static final String GET_PATH = "/api/v1/urls/{code}";
    private static final String REDIRECT_PATH = "/{code}";
    private static final String STATS_PATH = "/api/v1/urls/{code}/stats";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    private JsonNode docs;
    private JsonNode post;
    private JsonNode getOne;
    private JsonNode patchOne;
    private JsonNode deleteOne;
    private JsonNode redirect;

    @BeforeEach
    void loadDocs() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        docs = objectMapper.readTree(response.getBody());
        post = docs.path("paths").path(CREATE_PATH).path("post");
        getOne = docs.path("paths").path(GET_PATH).path("get");
        patchOne = docs.path("paths").path(GET_PATH).path("patch");
        deleteOne = docs.path("paths").path(GET_PATH).path("delete");
        redirect = docs.path("paths").path(REDIRECT_PATH).path("get");
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
    void shouldDescribeCreateRequestBodyWithOriginalUrlRequiredAndAliasAndExpiresAtOptional() {
        assertThat(post.isMissingNode()).as("POST %s documented", CREATE_PATH).isFalse();
        JsonNode content = post.path("requestBody").path("content");
        assertThat(names(content)).containsExactly("application/json");
        JsonNode schema = resolve(content.path("application/json").path("schema"));
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("originalUrl", "alias", "expiresAt");
        assertThat(schema.path("properties").path("expiresAt").path("format").asText()).isEqualTo("date-time");
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).contains("originalUrl").doesNotContain("alias").doesNotContain("expiresAt");
    }

    @Test
    void shouldDescribe201AsJsonOnlyWithTheTenFieldResourceAndLocationHeader() {
        JsonNode created = post.path("responses").path("201");
        assertThat(created.isMissingNode()).isFalse();
        assertThat(names(created.path("content"))).containsExactly("application/json");
        JsonNode schema = resolve(created.path("content").path("application/json").path("schema"));
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("shortCode", "shortUrl",
                "originalUrl", "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt", "expiresAt",
                "expired");
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

    // ---- US-007: GET /api/v1/urls/{code} ----

    @Test
    void shouldDocumentGetOperationWithExactlyOneCodePathParameter() {
        assertThat(getOne.isMissingNode()).as("GET %s documented", GET_PATH).isFalse();
        JsonNode parameters = getOne.path("parameters");
        assertThat(parameters.size()).isEqualTo(1);
        assertThat(parameters.get(0).path("name").asText()).isEqualTo("code");
        assertThat(parameters.get(0).path("in").asText()).isEqualTo("path");
        assertThat(parameters.get(0).path("required").asBoolean()).isTrue();
    }

    @Test
    void shouldDocumentGet200AsJsonOnlyWithTheSameTenPropertiesAsCreate() {
        JsonNode ok = getOne.path("responses").path("200");
        assertThat(ok.isMissingNode()).isFalse();
        assertThat(names(ok.path("content"))).containsExactly("application/json");
        JsonNode schema = resolve(ok.path("content").path("application/json").path("schema"));
        JsonNode createdSchema = resolve(post.path("responses").path("201").path("content")
                .path("application/json").path("schema"));
        assertThat(names(schema.path("properties"))).isEqualTo(names(createdSchema.path("properties")));
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("shortCode", "shortUrl",
                "originalUrl", "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt", "expiresAt",
                "expired");
    }

    @Test
    void shouldDocumentGetErrorResponsesAsProblemJsonOnlyWithTheProblemSchema() {
        for (String status : List.of("401", "404", "406")) {
            JsonNode response = getOne.path("responses").path(status);
            assertThat(response.isMissingNode()).as("response %s documented", status).isFalse();
            assertThat(names(response.path("content"))).as("media types of %s", status)
                    .containsExactly("application/problem+json");
            assertThat(response.path("content").path("application/problem+json").path("schema").path("$ref").asText())
                    .as("schema of %s", status).endsWith("/Problem");
        }
    }

    @Test
    void shouldApplyBasicAuthenticationToTheGetOperation() {
        assertThat(getOne.path("security").toString()).contains("basicAuth");
    }

    @Test
    void shouldDescribeTheCheckAfter409UseOfGet() {
        String description = getOne.path("description").asText();

        assertThat(description).contains("ALIAS_ALREADY_EXISTS");
        assertThat(description).contains("SHORT_URL_NOT_FOUND");
    }

    // ---- US-008: GET /{code} ----

    @Test
    void shouldDocumentTheRedirectPathWithExactlyTheGetOperation() {
        assertThat(redirect.isMissingNode()).as("GET %s documented", REDIRECT_PATH).isFalse();
        assertThat(names(docs.path("paths").path(REDIRECT_PATH))).containsExactly("get");
    }

    @Test
    void shouldDocumentExactlyOneRequiredCodePathParameterWithoutAPattern() {
        JsonNode parameters = redirect.path("parameters");

        assertThat(parameters.size()).isEqualTo(1);
        assertThat(parameters.get(0).path("name").asText()).isEqualTo("code");
        assertThat(parameters.get(0).path("in").asText()).isEqualTo("path");
        assertThat(parameters.get(0).path("required").asBoolean()).isTrue();
        assertThat(parameters.get(0).path("schema").has("pattern")).isFalse();
    }

    @Test
    void shouldDocumentExactlyThe302404And410ResponsesWithNoImplicit200() {
        assertThat(names(redirect.path("responses"))).containsExactly("302", "404", "410");
        assertThat(names(redirect.path("responses").path("410").path("content")))
                .containsExactly("application/problem+json");
    }

    @Test
    void shouldDocument302WithLocationAndCacheControlHeadersAndNoContent() {
        JsonNode found = redirect.path("responses").path("302");

        assertThat(names(found.path("headers"))).containsExactlyInAnyOrder("Location", "Cache-Control");
        assertThat(found.has("content")).isFalse();
    }

    @Test
    void shouldDocument404AsProblemJsonOnlyWithTheProblemSchema() {
        JsonNode notFound = redirect.path("responses").path("404");

        assertThat(names(notFound.path("content"))).containsExactly("application/problem+json");
        assertThat(notFound.path("content").path("application/problem+json").path("schema").path("$ref").asText())
                .endsWith("/Problem");
    }

    @Test
    void shouldRequireNoSecurityOnTheRedirectOperation() {
        assertThat(redirect.has("security")).isFalse();
        assertThat(docs.has("security")).isFalse();
    }

    // ---- US-009: PATCH and DELETE /api/v1/urls/{code} ----

    @Test
    void shouldDocumentExactlyTheGetPatchAndDeleteOperationsOnTheCodePath() {
        assertThat(names(docs.path("paths").path(GET_PATH))).containsExactlyInAnyOrder("get", "patch", "delete");
    }

    @Test
    void shouldDocumentPatchWithOneCodePathParameterAndAJsonOnlyRequestBodyWithActiveAndExpiresAtOptional() {
        assertThat(patchOne.isMissingNode()).as("PATCH %s documented", GET_PATH).isFalse();
        JsonNode parameters = patchOne.path("parameters");
        assertThat(parameters.size()).isEqualTo(1);
        assertThat(parameters.get(0).path("name").asText()).isEqualTo("code");
        assertThat(parameters.get(0).path("in").asText()).isEqualTo("path");
        assertThat(patchOne.path("requestBody").path("required").asBoolean()).isTrue();
        JsonNode content = patchOne.path("requestBody").path("content");
        assertThat(names(content)).containsExactly("application/json");
        JsonNode schema = resolve(content.path("application/json").path("schema"));
        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("active", "expiresAt");
        assertThat(schema.path("properties").path("active").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("properties").path("expiresAt").path("format").asText()).isEqualTo("date-time");
        // D114: either field may be omitted; a body with neither is rejected by the controller, not the schema.
        assertThat(schema.path("required").isMissingNode() || schema.path("required").isEmpty()).isTrue();
    }

    @Test
    void shouldDocumentPatchResponsesAsExactlyTheSevenExpectedStatuses() {
        assertThat(names(patchOne.path("responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "404", "406", "409", "415");
    }

    @Test
    void shouldDocumentPatch200AsJsonOnlyWithTheSameEightPropertiesAsCreate() {
        JsonNode ok = patchOne.path("responses").path("200");
        assertThat(names(ok.path("content"))).containsExactly("application/json");
        JsonNode schema = resolve(ok.path("content").path("application/json").path("schema"));
        JsonNode createdSchema = resolve(post.path("responses").path("201").path("content")
                .path("application/json").path("schema"));
        assertThat(names(schema.path("properties"))).isEqualTo(names(createdSchema.path("properties")));
    }

    @Test
    void shouldDocumentEveryPatchErrorResponseAsProblemJsonOnlyWithTheProblemSchema() {
        for (String status : List.of("400", "401", "404", "406", "409", "415")) {
            JsonNode response = patchOne.path("responses").path(status);
            assertThat(names(response.path("content"))).as("media types of PATCH %s", status)
                    .containsExactly("application/problem+json");
            assertThat(response.path("content").path("application/problem+json").path("schema").path("$ref").asText())
                    .as("schema of PATCH %s", status).endsWith("/Problem");
        }
    }

    @Test
    void shouldApplyBasicAuthenticationToPatchAndDelete() {
        assertThat(patchOne.path("security").toString()).contains("basicAuth");
        assertThat(deleteOne.path("security").toString()).contains("basicAuth");
    }

    @Test
    void shouldDocumentDeleteWithOneCodePathParameterAndNoRequestBody() {
        assertThat(deleteOne.isMissingNode()).as("DELETE %s documented", GET_PATH).isFalse();
        JsonNode parameters = deleteOne.path("parameters");
        assertThat(parameters.size()).isEqualTo(1);
        assertThat(parameters.get(0).path("name").asText()).isEqualTo("code");
        assertThat(parameters.get(0).path("in").asText()).isEqualTo("path");
        assertThat(deleteOne.has("requestBody")).isFalse();
    }

    @Test
    void shouldDocumentDeleteResponsesAsExactlyTheSixExpectedStatusesWithNoImplicit200() {
        assertThat(names(deleteOne.path("responses")))
                .containsExactlyInAnyOrder("204", "401", "403", "404", "406", "409");
    }

    @Test
    void shouldDocumentDelete204WithNoContent() {
        assertThat(deleteOne.path("responses").path("204").has("content")).isFalse();
    }

    @Test
    void shouldDocumentEveryDeleteErrorResponseAsProblemJsonOnlyWithTheProblemSchema() {
        for (String status : List.of("401", "403", "404", "406", "409")) {
            JsonNode response = deleteOne.path("responses").path(status);
            assertThat(names(response.path("content"))).as("media types of DELETE %s", status)
                    .containsExactly("application/problem+json");
            assertThat(response.path("content").path("application/problem+json").path("schema").path("$ref").asText())
                    .as("schema of DELETE %s", status).endsWith("/Problem");
        }
    }

    @Test
    void shouldDescribeTheAdminOnlySoftDeleteAndTheCodeNeverBeingReused() {
        String description = deleteOne.path("description").asText();

        assertThat(description).contains("ACCESS_DENIED").contains("ALIAS_ALREADY_EXISTS");
        assertThat(patchOne.path("description").asText()).contains("CONCURRENT_MODIFICATION")
                .contains("SHORT_URL_NOT_FOUND");
    }

    // ---- US-011: GET /api/v1/urls/{code}/stats ----

    private JsonNode stats() {
        return docs.path("paths").path(STATS_PATH).path("get");
    }

    private JsonNode statsParameter(String name) {
        for (JsonNode parameter : stats().path("parameters")) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        return objectMapper.missingNode();
    }

    @Test
    void shouldDocumentOnlyGetOnTheStatsPathWithExactlyTheFourDeclaredParameters() {
        assertThat(names(docs.path("paths").path(STATS_PATH))).containsExactly("get");
        List<String> parameters = new ArrayList<>();
        stats().path("parameters").forEach(p -> parameters.add(p.path("in").asText() + ":" + p.path("name").asText()));
        // No "query" map parameter leaks from the hidden MultiValueMap, and no authentication parameter either.
        assertThat(parameters).containsExactlyInAnyOrder("path:code", "query:timezone", "query:from", "query:to");
        assertThat(statsParameter("code").path("required").asBoolean()).isTrue();
        for (String optional : List.of("timezone", "from", "to")) {
            assertThat(statsParameter(optional).path("required").asBoolean()).as(optional).isFalse();
        }
    }

    @Test
    void shouldDocumentTheTimezoneRuleTheSignWarningAndThePlusEncoding() {
        JsonNode timezone = statsParameter("timezone");
        String description = timezone.path("description").asText();

        assertThat(timezone.path("schema").path("default").asText()).isEqualTo("UTC");
        assertThat(timezone.path("schema").path("type").asText()).isEqualTo("string");
        assertThat(description).contains("IANA").contains("case-sensitive").contains("+05:00").contains("UTC+5");
        // D96: Etc/GMT+5 is five hours BEHIND UTC, which the name does not suggest.
        assertThat(description).contains("Etc/GMT+5").contains("BEHIND UTC").contains("UTC-5");
        assertThat(description).contains("%2B");
    }

    @Test
    void shouldDocumentFromAndToAsOptionalDatesWithTheirDefaultsAndLimits() {
        for (String name : List.of("from", "to")) {
            JsonNode parameter = statsParameter(name);
            assertThat(parameter.path("schema").path("type").asText()).as(name).isEqualTo("string");
            assertThat(parameter.path("schema").path("format").asText()).as(name).isEqualTo("date");
            assertThat(parameter.path("description").asText()).as(name).contains("inclusive")
                    .contains("1970-01-01").contains("9999-12-31");
        }
        assertThat(statsParameter("from").path("description").asText()).contains("29 days before").contains("366");
        assertThat(statsParameter("to").path("description").asText()).contains("today");
    }

    @Test
    void shouldDocumentTheStatsResponsesAsExactlyTheFiveExpectedStatuses() {
        assertThat(names(stats().path("responses"))).containsExactlyInAnyOrder("200", "400", "401", "404", "406");
        for (String status : List.of("400", "401", "404", "406")) {
            JsonNode response = stats().path("responses").path(status);
            assertThat(names(response.path("content"))).as("media types of %s", status)
                    .containsExactly("application/problem+json");
            assertThat(response.path("content").path("application/problem+json").path("schema").path("$ref").asText())
                    .as("schema of %s", status).endsWith("/Problem");
        }
        assertThat(stats().path("responses").path("400").path("description").asText())
                .contains("VALIDATION_FAILED").contains("MALFORMED_REQUEST");
        assertThat(stats().path("responses").path("404").path("description").asText())
                .contains("SHORT_URL_NOT_FOUND");
    }

    @Test
    void shouldDocumentTheStats200AsJsonOnlyWithTheD101FieldsAndTheDailyEntryShape() {
        JsonNode ok = stats().path("responses").path("200");
        assertThat(names(ok.path("content"))).containsExactly("application/json");
        JsonNode schema = resolve(ok.path("content").path("application/json").path("schema"));

        assertThat(names(schema.path("properties"))).containsExactlyInAnyOrder("shortCode", "timezone", "from", "to",
                "totalClicks", "clicksInRange", "lastAccessedAt", "daily", "expiresAt", "expired");
        JsonNode daily = schema.path("properties").path("daily");
        assertThat(daily.path("type").asText()).isEqualTo("array");
        assertThat(names(resolve(daily.path("items")).path("properties"))).containsExactlyInAnyOrder("date", "clicks");
    }

    @Test
    void shouldApplyBasicAuthenticationAndDescribeLocalDaysAndTheUtcLastAccess() {
        assertThat(stats().path("security").toString()).contains("basicAuth");
        String description = stats().path("description").asText();

        assertThat(description).contains("23 or 25 hours").contains("UTC instant").contains("DEACTIVATED")
                .contains("SHORT_URL_NOT_FOUND").contains("MALFORMED_REQUEST");
    }
}
