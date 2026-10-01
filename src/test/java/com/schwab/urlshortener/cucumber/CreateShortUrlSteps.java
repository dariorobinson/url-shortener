package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.config.ShortCodeProperties;
import com.schwab.urlshortener.support.ApiClient;
import com.schwab.urlshortener.support.EncodedUrls;
import com.schwab.urlshortener.support.ScriptedShortCodeGenerator;
import com.schwab.urlshortener.support.ShortUrlTestData;
import com.schwab.urlshortener.support.TestUsers;
import com.schwab.urlshortener.util.validation.LocationEncoder;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps for {@code create-short-url.feature} (US-006). Everything goes through real HTTP; the
 * database is touched only to arrange rows the API cannot create (colliding codes in a given
 * status) and to assert what was, or was not, committed. Credentials come from the shared test
 * users and are never printed.
 */
public class CreateShortUrlSteps {

    private static final Set<String> RESOURCE_KEYS = Set.of("shortCode", "shortUrl", "originalUrl", "status",
            "customAlias", "clickCount", "createdAt", "lastAccessedAt",
            "expiresAt", "expired");
    private static final Set<String> RESERVED = Set.of("api", "actuator", "v3", "error", "health", "admin",
            "login", "logout", "static", "assets", "docs");
    private static final List<String> INTERNAL_MARKERS = List.of("Exception", "com.fasterxml", "com.schwab",
            "org.springframework", "org.postgresql", "org.hibernate", "JSON parse", "Unexpected", "at line",
            "column", "SQL", "constraint", "uk_short_url", "Caused by", "stackTrace");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedShortCodeGenerator generator;

    @Autowired
    private ShortCodeProperties properties;

    private ApiClient client;
    private ShortUrlTestData data;
    private String user;
    private String loginSpelling;
    private HttpResponse<String> response;
    private final List<HttpResponse<String>> responses = new ArrayList<>();
    private List<HttpResponse<String>> raceResponses = List.of();
    private String raceAlias;
    private String submittedUrl;
    private JsonNode apiDocs;
    private JsonNode createOperation;

    private ApiClient client() {
        if (client == null) {
            client = new ApiClient(port, objectMapper);
            data = new ShortUrlTestData(jdbc);
        }
        return client;
    }

    private ShortUrlTestData data() {
        client();
        return data;
    }

    private void record(HttpResponse<String> r) {
        response = r;
        responses.add(r);
    }

    private static String aliasFromCell(String cell) {
        return switch (cell) {
            case "(empty)" -> "";
            case "(three spaces)" -> "   ";
            default -> cell;
        };
    }

    /** POST as the signed-in caller, using the login spelling the scenario gave. */
    private HttpResponse<String> postAsCaller(String body, String... headers) throws Exception {
        if (user == null || loginSpelling.equals(user)) {
            return client().post(user, body, headers);
        }
        List<String> all = new ArrayList<>(List.of(headers));
        all.add("Authorization");
        all.add(ApiClient.basicHeader(loginSpelling, user));
        return client().post(null, body, all.toArray(String[]::new));
    }

    // ---- Given ----

    @Given("{string} is signed in")
    public void isSignedIn(String username) {
        // Usernames match case-insensitively (US-006 AC10): "ALICE" signs in as the account alice.
        user = TestUsers.require(username.toLowerCase(Locale.ROOT));
        loginSpelling = username;
    }

    @Given("the caller is not signed in")
    public void theCallerIsNotSignedIn() {
        user = null;
        loginSpelling = null;
    }

    @Given("a short URL with code {string} already exists")
    public void aShortUrlWithCodeAlreadyExists(String code) {
        data().seed(code, "ACTIVE");
    }

    @Given("a {word} short URL with code {string} already exists")
    public void aShortUrlWithStatusAndCodeAlreadyExists(String status, String code) {
        data().seed(code, status);
    }

    @Given("short URLs already exist for every allowed attempt and the generator will offer exactly those codes")
    public void everyAllowedAttemptCollides() {
        String[] codes = new String[properties.maxAttempts()];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = String.format("Full%03d", i + 1);
            data().seed(codes[i], "ACTIVE");
        }
        generator.willReturn(codes);
    }

    @Given("the next generated short codes are {string}")
    public void theNextGeneratedShortCodesAre(String codes) {
        generator.willReturn(codes.split(","));
    }

    // ---- When ----

    @When("the caller creates a short URL for {string}")
    public void theCallerCreatesAShortUrlFor(String url) throws Exception {
        record(postAsCaller(client().createBody(url, null)));
    }

    @When("the caller creates a short URL for {string} again")
    public void theCallerCreatesAShortUrlForAgain(String url) throws Exception {
        record(postAsCaller(client().createBody(url, null)));
    }

    @When("the caller creates a short URL for {string} with alias {string}")
    public void theCallerCreatesAShortUrlWithAlias(String url, String alias) throws Exception {
        record(postAsCaller(client().createBody(url, aliasFromCell(alias))));
    }

    @When("the caller creates a short URL for {string} accepting {string}")
    public void theCallerCreatesAShortUrlAccepting(String url, String accept) throws Exception {
        record(postAsCaller(client().createBody(url, null), "Accept", accept));
    }

    @When("the caller creates a short URL for {string} with alias {string} accepting {string}")
    public void theCallerCreatesAShortUrlWithAliasAccepting(String url, String alias, String accept)
            throws Exception {
        record(postAsCaller(client().createBody(url, alias), "Accept", accept));
    }

    @When("the caller creates a short URL for {string} pretending to be host {string}")
    public void theCallerCreatesAShortUrlPretendingToBeHost(String url, String host) throws Exception {
        // Needs -Djdk.httpclient.allowRestrictedHeaders=host on the Failsafe JVM (D66); without it the
        // JDK client throws IllegalArgumentException here, so this step cannot pass vacuously.
        record(postAsCaller(client().createBody(url, null), "Host", host, "X-Forwarded-Host", host));
    }

    @When("the caller creates a short URL of {int} characters")
    public void theCallerCreatesAShortUrlOfCharacters(int length) throws Exception {
        String prefix = "https://example.com/";
        record(postAsCaller(client().createBody(prefix + "a".repeat(length - prefix.length()), null)));
    }

    @When("the caller creates a short URL of {int} encoded bytes made of {word} characters")
    public void theCallerCreatesAShortUrlOfEncodedBytes(int encodedBytes, String kind) throws Exception {
        submittedUrl = EncodedUrls.withEncodedLength(EncodedUrls.unitFor(kind), encodedBytes);
        assertThat(LocationEncoder.encode(submittedUrl)).hasSize(encodedBytes);
        assertThat(submittedUrl.length()).as("within the D11 character limit").isLessThanOrEqualTo(2048);
        record(postAsCaller(client().createBody(submittedUrl, null)));
    }

    @When("the caller posts the raw JSON body {string}")
    public void theCallerPostsTheRawJsonBody(String body) throws Exception {
        record(postAsCaller(body));
    }

    @When("\"alice\" and \"bob\" create a short URL with alias {string} at the same time")
    public void aliceAndBobCreateAtTheSameTime(String alias) throws Exception {
        raceAlias = alias;
        CyclicBarrier barrier = new CyclicBarrier(2);
        String body = client().createBody("https://example.com/race", alias);
        Callable<HttpResponse<String>> alice = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return client().post("alice", body);
        };
        Callable<HttpResponse<String>> bob = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return client().post("bob", body);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> futures = pool.invokeAll(List.of(alice, bob), 30, TimeUnit.SECONDS);
            List<HttpResponse<String>> results = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) {
                results.add(f.get());
            }
            raceResponses = results;
        } finally {
            pool.shutdownNow();
        }
    }

    @When("anyone requests the API documentation")
    public void anyoneRequestsTheApiDocumentation() throws Exception {
        HttpResponse<String> docs = client().get("/v3/api-docs");
        assertThat(docs.statusCode()).isEqualTo(200);
        apiDocs = objectMapper.readTree(docs.body());
        createOperation = apiDocs.path("paths").path("/api/v1/urls").path("post");
    }

    // ---- Then: status and problem ----

    @Then("the service answers with status {int}")
    public void theServiceAnswersWithStatus(int status) {
        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Then("the problem has error code {string}")
    public void theProblemHasErrorCode(String errorCode) throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        assertThat(client().json(response).path("errorCode").asText()).isEqualTo(errorCode);
    }

    @Then("the problem names the field {string}")
    public void theProblemNamesTheField(String field) throws Exception {
        JsonNode errors = client().json(response).path("errors");
        assertThat(errors.isArray()).isTrue();
        assertThat(errors).anySatisfy(e -> assertThat(e.path("field").asText()).isEqualTo(field));
    }

    @Then("the problem body reveals no internals")
    public void theProblemBodyRevealsNoInternals() {
        assertThat(response.body()).isNotBlank();
        for (String marker : INTERNAL_MARKERS) {
            assertThat(response.body()).as("body must not contain %s", marker).doesNotContain(marker);
        }
    }

    @Then("the response has no Location header")
    public void theResponseHasNoLocationHeader() {
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    // ---- Then: created resource ----

    private JsonNode created() throws Exception {
        return client().json(response);
    }

    @Then("the Location header is the management resource of the new short code")
    public void theLocationHeaderIsTheManagementResource() throws Exception {
        assertThat(response.headers().firstValue("Location"))
                .contains("/api/v1/urls/" + created().path("shortCode").asText());
    }

    @Then("the created resource has exactly the documented fields")
    public void theCreatedResourceHasExactlyTheDocumentedFields() throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
        assertThat(ApiClient.keys(created())).isEqualTo(new java.util.TreeSet<>(RESOURCE_KEYS));
        assertThat(Instant.parse(created().path("createdAt").asText())).isNotNull();
    }

    @Then("the created resource has status {string} and customAlias false and clickCount 0 and no lastAccessedAt")
    public void theCreatedResourceHasDefaults(String status) throws Exception {
        JsonNode body = created();
        assertThat(body.path("status").asText()).isEqualTo(status);
        assertThat(body.path("customAlias").isBoolean()).isTrue();
        assertThat(body.path("customAlias").asBoolean()).isFalse();
        assertThat(body.path("clickCount").asLong(-1)).isZero();
        assertThat(body.has("lastAccessedAt")).isTrue();
        assertThat(body.get("lastAccessedAt").isNull()).isTrue();
    }

    @Then("the created resource has customAlias true")
    public void theCreatedResourceHasCustomAliasTrue() throws Exception {
        assertThat(created().path("customAlias").asBoolean(false)).isTrue();
    }

    @Then("the created resource points at {string}")
    public void theCreatedResourcePointsAt(String url) throws Exception {
        assertThat(created().path("originalUrl").asText()).isEqualTo(url);
    }

    @Then("the short link is built from the configured base URL")
    public void theShortLinkIsBuiltFromTheConfiguredBaseUrl() throws Exception {
        // The test profile's app.base-url is https://short.example (D62); the server itself listens on localhost.
        assertThat(created().path("shortUrl").asText())
                .isEqualTo("https://short.example/" + created().path("shortCode").asText());
    }

    @Then("the created short code is {string}")
    public void theCreatedShortCodeIs(String code) throws Exception {
        assertThat(created().path("shortCode").asText()).isEqualTo(code);
    }

    @Then("the created short code is not {string}")
    public void theCreatedShortCodeIsNot(String code) throws Exception {
        assertThat(created().path("shortCode").asText()).isNotBlank().isNotEqualTo(code);
    }

    @Then("the created short code is not a reserved word")
    public void theCreatedShortCodeIsNotAReservedWord() throws Exception {
        assertThat(RESERVED).doesNotContain(created().path("shortCode").asText().toLowerCase(Locale.ROOT));
    }

    @Then("the created short URL is recorded as created by {string}")
    public void theCreatedShortUrlIsRecordedAsCreatedBy(String owner) throws Exception {
        assertThat(data().createdBy(created().path("shortCode").asText())).isEqualTo(owner);
    }

    @Then("the created resource does not expose its owner")
    public void theCreatedResourceDoesNotExposeItsOwner() {
        assertThat(response.body()).doesNotContain("createdBy").doesNotContain("created_by");
    }

    @Then("both creations answered 201 with different short codes")
    public void bothCreationsAnswered201WithDifferentShortCodes() throws Exception {
        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        assertThat(client().json(responses.get(0)).path("shortCode").asText())
                .isNotBlank()
                .isNotEqualTo(client().json(responses.get(1)).path("shortCode").asText());
    }

    @Then("nothing in the response mentions {string}")
    public void nothingInTheResponseMentions(String text) {
        assertThat(response.body()).doesNotContain(text);
        assertThat(response.headers().map().toString()).doesNotContain(text);
    }

    // ---- Then: database state ----

    @Then("no short URL exists for the submitted original URL")
    public void noShortUrlExistsForTheSubmittedOriginalUrl() {
        assertThat(submittedUrl).isNotNull();
        assertThat(data().countByOriginalUrl(submittedUrl)).isZero();
    }

    @Then("exactly one short URL exists for the submitted original URL")
    public void exactlyOneShortUrlExistsForTheSubmittedOriginalUrl() {
        assertThat(submittedUrl).isNotNull();
        assertThat(data().countByOriginalUrl(submittedUrl)).isEqualTo(1);
    }

    @Then("no short URL exists for original URL {string}")
    public void noShortUrlExistsForOriginalUrl(String url) {
        assertThat(data().countByOriginalUrl(url)).isZero();
    }

    @Then("exactly {int} short URL(s) exist(s) for original URL {string}")
    public void exactlyShortUrlsExistForOriginalUrl(int expected, String url) {
        assertThat(data().countByOriginalUrl(url)).isEqualTo(expected);
    }

    @Then("exactly {int} short URL(s) exist(s) with code {string}")
    public void exactlyShortUrlsExistWithCode(int expected, String code) {
        assertThat(data().countByCode(code)).isEqualTo(expected);
    }

    @Then("no short URL exists with code {string}")
    public void noShortUrlExistsWithCode(String code) {
        assertThat(data().countByCodeIgnoringCase(code)).isZero();
    }

    @Then("the generator was asked for the maximum number of attempts")
    public void theGeneratorWasAskedForTheMaximumNumberOfAttempts() {
        assertThat(generator.calls()).isEqualTo(properties.maxAttempts());
    }

    @Then("the generator was asked for {int} codes")
    public void theGeneratorWasAskedForCodes(int calls) {
        assertThat(generator.calls()).isEqualTo(calls);
    }

    // ---- Then: race ----

    @Then("exactly one of them gets 201 and the other gets 409 with error code {string}")
    public void exactlyOneGets201AndTheOtherGets409(String errorCode) throws Exception {
        assertThat(raceResponses).hasSize(2);
        assertThat(raceResponses.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(201, 409);
        HttpResponse<String> loser = raceResponses.stream().filter(r -> r.statusCode() == 409).findFirst()
                .orElseThrow();
        assertThat(client().json(loser).path("errorCode").asText()).isEqualTo(errorCode);
    }

    @Then("the short URL {string} is owned by the caller who got 201")
    public void theShortUrlIsOwnedByTheCallerWhoGot201(String code) {
        // The two racing requests are sent in a fixed order [alice, bob], so index 0 is alice.
        String winner = raceResponses.get(0).statusCode() == 201 ? "alice" : "bob";
        assertThat(raceAlias).isEqualTo(code);
        assertThat(data().createdBy(code)).isEqualTo(winner);
    }

    // ---- Then: documentation ----

    @Then("the documentation describes POST {string} with Basic authentication")
    public void theDocumentationDescribesPost(String path) throws Exception {
        assertThat(apiDocs.path("paths").path(path).path("post").isMissingNode()).isFalse();
        assertThat(createOperation.path("requestBody").path("content").has("application/json")).isTrue();
        assertThat(createOperation.path("security").toString()).contains("basicAuth");
        assertThat(apiDocs.path("components").path("securitySchemes").path("basicAuth").path("scheme").asText())
                .isEqualTo("basic");
    }

    @Then("the documentation lists 201, 400, 401, 406, 409, 415 and 503 for the operation")
    public void theDocumentationListsResponses() {
        for (String status : List.of("201", "400", "401", "406", "409", "415", "503")) {
            assertThat(createOperation.path("responses").has(status)).as("response %s documented", status).isTrue();
        }
    }

    @Then("the documentation says internationalised hosts must be submitted as punycode")
    public void theDocumentationMentionsPunycode() {
        assertThat(createOperation.path("description").asText()).containsIgnoringCase("punycode");
    }
}
