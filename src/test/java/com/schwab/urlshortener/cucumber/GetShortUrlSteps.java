package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ApiClient;
import com.schwab.urlshortener.support.ShortUrlTestData;
import com.schwab.urlshortener.support.TestUsers;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps for {@code get-short-url.feature} (US-007). Everything goes through real HTTP; rows the API
 * cannot create (other owners' links in any status, click data) are arranged with raw SQL. Credentials
 * come from the shared test users and are never printed.
 */
public class GetShortUrlSteps {

    private static final String BASE = "/api/v1/urls/";
    private static final Set<String> RESOURCE_KEYS = Set.of("shortCode", "shortUrl", "originalUrl",
            "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient client;
    private ShortUrlTestData data;
    private HttpResponse<String> response;
    private HttpResponse<String> created;
    private HttpResponse<String> remembered;
    private Map<String, Object> rememberedState;
    private final List<Integer> statusesSinceRemembered = new ArrayList<>();

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

    private HttpResponse<String> record(HttpResponse<String> served) {
        statusesSinceRemembered.add(served.statusCode());
        return served;
    }

    private JsonNode body() throws Exception {
        return client().json(response);
    }

    // ---- Given ----

    @Given("{word} owns a short URL {string} for {string} with status {string}")
    public void ownsAShortUrl(String owner, String code, String url, String status) {
        data().seed(code, status, url, TestUsers.require(owner));
    }

    @Given("the short URL {string} has {int} clicks, the last at {string}")
    public void theShortUrlHasClicks(String code, int clicks, String lastAccess) {
        data().seedClicks(code, clicks, Instant.parse(lastAccess));
    }

    @Given("{word} creates a short URL for {string}")
    public void createsAShortUrl(String user, String url) throws Exception {
        created = client().post(user, client().createBody(url, null));
        assertThat(created.statusCode()).isEqualTo(201);
    }

    @Given("{word} creates a short URL for {string} with alias {string}")
    public void createsAShortUrlWithAlias(String user, String url, String alias) throws Exception {
        created = client().post(user, client().createBody(url, alias));
        assertThat(created.statusCode()).isEqualTo(201);
    }

    @Given("{word} creates a short URL for {string} with alias {string} and gets 409")
    public void createsAShortUrlWithAliasAndGets409(String user, String url, String alias) throws Exception {
        HttpResponse<String> refused = client().post(user, client().createBody(url, alias));
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(client().json(refused).path("errorCode").asText()).isEqualTo("ALIAS_ALREADY_EXISTS");
    }

    // ---- When ----

    @When("{word} requests the details of {string}")
    public void requestsTheDetailsOf(String user, String code) throws Exception {
        response = record(client().send("GET", BASE + code, user, null, null));
    }

    @When("{word} requests the details of the created short URL")
    public void requestsTheDetailsOfTheCreatedShortUrl(String user) throws Exception {
        String code = client().json(created).path("shortCode").asText();
        response = record(client().send("GET", BASE + code, user, null, null));
    }

    @When("{word} requests the details of {string} accepting {string}")
    public void requestsTheDetailsAccepting(String user, String code, String accept) throws Exception {
        response = record(client().send("GET", BASE + code, user, null, null, "Accept", accept));
    }

    @When("{word} requests the details of {string} with a wrong password")
    public void requestsTheDetailsWithAWrongPassword(String user, String code) throws Exception {
        String token = Base64.getEncoder()
                .encodeToString((TestUsers.require(user) + ":not-the-password").getBytes(StandardCharsets.UTF_8));
        response = client().send("GET", BASE + code, null, null, null, "Authorization", "Basic " + token);
    }

    @When("an anonymous caller requests the details of {string}")
    public void anAnonymousCallerRequestsTheDetailsOf(String code) throws Exception {
        response = client().send("GET", BASE + code, null, null, null);
    }

    @When("{word} sends HEAD for the details of {string}")
    public void sendsHeadForTheDetailsOf(String user, String code) throws Exception {
        response = record(client().send("HEAD", BASE + code, user, null, null));
    }

    @When("the details response is remembered")
    public void theDetailsResponseIsRemembered() {
        remembered = response;
    }

    @When("the stored state of {string} is remembered")
    public void theStoredStateIsRemembered(String code) {
        rememberedState = data().rowState(code);
        statusesSinceRemembered.clear();
    }

    // ---- Then ----

    @Then("the details response status is {int}")
    public void theDetailsResponseStatusIs(int status) {
        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Then("the details response has error code {string}")
    public void theDetailsResponseHasErrorCode(String errorCode) throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        assertThat(body().path("errorCode").asText()).isEqualTo(errorCode);
    }

    @Then("the details response has exactly the documented fields")
    public void hasExactlyTheDocumentedFields() throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
        assertThat(ApiClient.keys(body())).isEqualTo(RESOURCE_KEYS);
        assertThat(Instant.parse(body().path("createdAt").asText())).isNotNull();
        assertThat(body().path("shortUrl").asText()).isEqualTo("https://short.example/" + body().path("shortCode").asText());
    }

    @Then("the details response has status {string} and points at {string}")
    public void hasStatusAndPointsAt(String status, String url) throws Exception {
        assertThat(body().path("status").asText()).isEqualTo(status);
        assertThat(body().path("originalUrl").asText()).isEqualTo(url);
    }

    @Then("the details response has no clicks yet")
    public void hasNoClicksYet() throws Exception {
        assertThat(body().path("clickCount").asLong(-1)).isZero();
        assertThat(body().has("lastAccessedAt")).isTrue();
        assertThat(body().get("lastAccessedAt").isNull()).isTrue();
    }

    @Then("the details response shows {int} clicks and a last access at {string}")
    public void showsClicks(int clicks, String lastAccess) throws Exception {
        assertThat(body().path("clickCount").asLong(-1)).isEqualTo(clicks);
        assertThat(Instant.parse(body().path("lastAccessedAt").asText())).isEqualTo(Instant.parse(lastAccess));
    }

    @Then("the details response does not expose the owner, the id, the update time or the version")
    public void doesNotExposeInternals() throws Exception {
        for (String field : new String[] {"createdBy", "id", "updatedAt", "version"}) {
            assertThat(body().has(field)).as("field %s", field).isFalse();
        }
    }

    @Then("the details response equals the create response")
    public void equalsTheCreateResponse() throws Exception {
        assertThat(body()).isEqualTo(client().json(created));
    }

    @Then("the details response reveals nothing about the short URL")
    public void revealsNothing() {
        assertThat(response.body()).doesNotContain("example.com").doesNotContain("alice").doesNotContain("originalUrl")
                .doesNotContain("clickCount").doesNotContain("ACTIVE").doesNotContain("createdBy");
    }

    @Then("the details response is identical to the remembered one")
    public void isIdenticalToTheRememberedOne() {
        assertThat(remembered.statusCode()).isEqualTo(404);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(remembered.body());
        assertThat(ApiClient.contentType(response)).isEqualTo(ApiClient.contentType(remembered));
    }

    @Then("the details response has a Basic challenge")
    public void hasABasicChallenge() {
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Basic"));
    }

    @Then("the details response must not be cached")
    public void mustNotBeCached() {
        assertThat(response.headers().firstValue("Cache-Control"))
                .contains("no-cache, no-store, max-age=0, must-revalidate");
    }

    @Then("the details response has no body")
    public void hasNoBody() {
        assertThat(response.body()).isEmpty();
    }

    @Then("the stored state of {string} is unchanged")
    public void theStoredStateIsUnchanged(String code) {
        // Non-vacuity: every request since the state was remembered was served (200), so the handler ran.
        assertThat(statusesSinceRemembered).isNotEmpty().allMatch(status -> status == 200);
        assertThat(rememberedState).isNotNull().isNotEmpty();
        assertThat(data().rowState(code)).isEqualTo(rememberedState);
    }
}
