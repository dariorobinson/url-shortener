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
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps for {@code expiration.feature} (US-016). Real HTTP; the clock, the redirect and the lifecycle steps are the
 * shared ones from the other step classes. Users are resolved through {@link TestUsers#require}; database state is
 * read directly where the API cannot express "nothing was written".
 */
public class ExpirationSteps {

    private static final String BASE = "/api/v1/urls/";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient client;
    private ShortUrlTestData data;
    private HttpResponse<String> response;

    private ApiClient client() {
        if (client == null) {
            client = new ApiClient(port, objectMapper);
            data = new ShortUrlTestData(jdbc);
        }
        return client;
    }

    private static String createBody(String alias, String expiresAtJson) {
        return "{\"originalUrl\":\"https://example.com/" + alias + "\",\"alias\":\"" + alias + "\""
                + (expiresAtJson == null ? "" : ",\"expiresAt\":" + expiresAtJson) + "}";
    }

    private JsonNode body() throws Exception {
        return client().json(response);
    }

    private Instant storedExpiry(String code) {
        client();
        Timestamp value = jdbc.queryForObject("SELECT expires_at FROM short_url WHERE short_code = ?",
                Timestamp.class, code);
        return value == null ? null : value.toInstant();
    }

    // ---- Given / When

    @Given("{word} has a short URL {string} expiring at {string}")
    public void hasAShortUrlExpiringAt(String user, String code, String expiresAt) throws Exception {
        HttpResponse<String> created =
                client().post(TestUsers.require(user), createBody(code, "\"" + expiresAt + "\""));
        assertThat(created.statusCode()).isEqualTo(201);
    }

    @When("{word} creates a short URL {string} with the expiry value {string}")
    public void createsWithExpiryValue(String user, String code, String rawJsonValue) throws Exception {
        response = client().post(TestUsers.require(user), createBody(code, rawJsonValue));
    }

    @When("{word} creates a short URL {string} without an expiry")
    public void createsWithoutExpiry(String user, String code) throws Exception {
        response = client().post(TestUsers.require(user), createBody(code, null));
    }

    @When("{word} changes the expiry of {string} with the body {string}")
    public void changesTheExpiry(String user, String code, String body) throws Exception {
        response = client().send("PATCH", BASE + code, TestUsers.require(user), "application/json", body);
    }

    @When("{word} reads the details of {string} for expiry")
    public void readsDetails(String user, String code) throws Exception {
        response = client().send("GET", BASE + code, TestUsers.require(user), null, null);
    }

    @When("{word} reads the stats of {string} for expiry")
    public void readsStats(String user, String code) throws Exception {
        response = client().send("GET", BASE + code + "/stats", TestUsers.require(user), null, null);
    }

    // ---- Then

    @Then("the expiry response status is {int}")
    public void theExpiryResponseStatusIs(int status) {
        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Then("the expiry response has error code {string}")
    public void theExpiryResponseHasErrorCode(String errorCode) throws Exception {
        assertThat(body().path("errorCode").asText()).isEqualTo(errorCode);
    }

    @Then("the expiry response names only the field {string}")
    public void theExpiryResponseNamesTheField(String field) throws Exception {
        assertThat(body().path("errors")).hasSize(1);
        assertThat(body().path("errors").get(0).path("field").asText()).isEqualTo(field);
    }

    @Then("the expiry response shows the expiry {string} and expired {word}")
    public void theExpiryResponseShows(String expiresAt, String expired) throws Exception {
        assertThat(body().path("expiresAt").asText()).isEqualTo(expiresAt);
        assertThat(body().path("expired").asBoolean()).isEqualTo(Boolean.parseBoolean(expired));
    }

    @Then("the expiry response shows no expiry and expired false")
    public void theExpiryResponseShowsNoExpiry() throws Exception {
        assertThat(body().has("expiresAt")).isTrue();
        assertThat(body().path("expiresAt").isNull()).isTrue();
        assertThat(body().path("expired").asBoolean()).isFalse();
    }

    @Then("the stored expiry of {string} is {string}")
    public void theStoredExpiryIs(String code, String expiresAt) {
        assertThat(storedExpiry(code)).isEqualTo(Instant.parse(expiresAt));
    }

    @Then("the stored expiry of {string} is cleared")
    public void theStoredExpiryIsCleared(String code) {
        assertThat(storedExpiry(code)).isNull();
    }

    @Then("no short URL was stored with code {string}")
    public void noShortUrlWasStored(String code) {
        client();
        assertThat(data.countByCode(code)).isZero();
    }
}
