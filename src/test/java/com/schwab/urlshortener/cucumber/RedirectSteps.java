package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schwab.urlshortener.support.ApiClient;
import com.schwab.urlshortener.support.ShortUrlTestData;
import com.schwab.urlshortener.support.TestUsers;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps for {@code redirect.feature} (US-008). Real HTTP with redirects never followed; rows are arranged
 * with the shared Given from the get-short-url steps or through the create API. Credentials come from the
 * shared test users and are never printed.
 */
public class RedirectSteps {

    private static final String UNKNOWN_CODE = "Unknown0";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient client;
    private ShortUrlTestData data;
    private HttpResponse<String> response;
    private HttpResponse<String> remembered;
    private String createdCode;
    private Map<String, Object> notedRow;
    private final List<Integer> statusesSinceNoted = new ArrayList<>();

    private ApiClient client() {
        if (client == null) {
            client = new ApiClient(port, objectMapper);
            data = new ShortUrlTestData(jdbc);
        }
        return client;
    }

    private static Map<String, List<String>> stableHeaders(HttpResponse<String> served) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        served.headers().map().forEach((name, values) -> {
            if (!"date".equalsIgnoreCase(name)) {
                headers.put(name, values);
            }
        });
        return headers;
    }

    private HttpResponse<String> send(String method, String path, String user, String... headers) throws Exception {
        HttpResponse<String> served = client().send(method, path, user, null, null, headers);
        statusesSinceNoted.add(served.statusCode());
        return served;
    }

    // ---- When ----

    @When("an anonymous visitor follows the short link {string}")
    public void anAnonymousVisitorFollows(String code) throws Exception {
        response = send("GET", "/" + code, null);
    }

    @When("an anonymous visitor follows the short link {string} accepting {string}")
    public void anAnonymousVisitorFollowsAccepting(String code, String accept) throws Exception {
        response = send("GET", "/" + code, null, "Accept", accept);
    }

    @When("an anonymous visitor sends HEAD for the short link {string}")
    public void anAnonymousVisitorSendsHead(String code) throws Exception {
        response = send("HEAD", "/" + code, null);
    }

    @When("an anonymous visitor sends GET to {string}")
    public void anAnonymousVisitorSendsGet(String path) throws Exception {
        response = send("GET", path, null);
    }

    @When("{word} sends GET to {string}")
    public void aUserSendsGet(String user, String path) throws Exception {
        response = send("GET", path, TestUsers.require(user));
    }

    @When("{word} shortens {string} through the API")
    public void shortens(String user, String url) throws Exception {
        HttpResponse<String> created = client().post(TestUsers.require(user), client().createBody(url, null));
        assertThat(created.statusCode()).isEqualTo(201);
        createdCode = client().json(created).path("shortCode").asText();
        assertThat(client().json(created).path("originalUrl").asText()).isEqualTo(url);
    }

    @When("an anonymous visitor follows the created short link")
    public void anAnonymousVisitorFollowsTheCreatedLink() throws Exception {
        assertThat(createdCode).isNotBlank();
        response = send("GET", "/" + createdCode, null);
    }

    @When("{word} follows the short link {string} with a wrong password")
    public void followsWithAWrongPassword(String user, String code) throws Exception {
        response = send("GET", "/" + code, null, "Authorization",
                ApiClient.rawBasicHeader(TestUsers.require(user), "not-the-password"));
    }

    @When("the short URL {string} is set to status {string}")
    public void theShortUrlIsSetToStatus(String code, String status) {
        client();
        data.setStatus(code, status);
    }

    @When("the redirect response is remembered")
    public void theRedirectResponseIsRemembered() {
        remembered = response;
    }

    @When("the stored row of {string} is noted")
    public void theStoredRowIsNoted(String code) {
        client();
        notedRow = data.rowState(code);
        statusesSinceNoted.clear();
    }

    // ---- Then ----

    @Then("the redirect response status is {int}")
    public void statusIs(int status) {
        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Then("the redirect response location is exactly {string}")
    public void locationIsExactly(String location) {
        assertThat(response.headers().allValues("Location")).containsExactly(location);
    }

    @Then("the redirect response cache control is exactly {string}")
    public void cacheControlIsExactly(String value) {
        assertThat(response.headers().allValues("Cache-Control")).containsExactly(value);
    }

    @Then("the redirect response has no Pragma or Expires header")
    public void hasNoPragmaOrExpires() {
        assertThat(response.headers().allValues("Pragma")).isEmpty();
        assertThat(response.headers().allValues("Expires")).isEmpty();
    }

    @Then("the redirect response has an empty body and no content type")
    public void hasEmptyBodyAndNoContentType() {
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("Content-Type")).isEmpty();
    }

    @Then("the redirect response has no Location header")
    public void hasNoLocation() {
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Then("the redirect response has error code {string}")
    public void hasErrorCode(String errorCode) throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        assertThat(client().json(response).path("errorCode").asText()).isEqualTo(errorCode);
    }

    @Then("the redirect response has no error code {string}")
    public void hasNoErrorCode(String errorCode) throws Exception {
        boolean problem = ApiClient.contentType(response).startsWith("application/problem+json");
        assertThat(problem ? client().json(response).path("errorCode").asText() : "").isNotEqualTo(errorCode);
    }

    @Then("the redirect response is the same problem as for an unknown code, except for its instance")
    public void isTheSameProblemAsForAnUnknownCode() throws Exception {
        HttpResponse<String> unknown = client().send("GET", "/" + UNKNOWN_CODE, null, null, null);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(response.statusCode()).isEqualTo(404);
        ObjectNode expected = (ObjectNode) client().json(unknown).deepCopy();
        ObjectNode actual = (ObjectNode) client().json(response).deepCopy();
        JsonNode instance = actual.remove("instance");
        expected.remove("instance");
        assertThat(instance).isNotNull();
        assertThat(actual).isEqualTo(expected);
        assertThat(ApiClient.contentType(response)).isEqualTo(ApiClient.contentType(unknown));
        assertThat(stableHeaders(response)).isEqualTo(stableHeaders(unknown));
    }

    @Then("the redirect response has the same headers as the remembered one")
    public void hasTheSameHeadersAsTheRememberedOne() {
        assertThat(remembered).isNotNull();
        Map<String, List<String>> expected = stableHeaders(remembered);
        Map<String, List<String>> actual = stableHeaders(response);
        expected.remove("content-length");
        actual.remove("content-length");
        assertThat(actual).isEqualTo(expected);
    }

    @Then("the stored row of {string} is unchanged after the redirects")
    public void theStoredRowIsUnchanged(String code) {
        // Non-vacuity: every request since the row was noted was a redirect, so the handler really ran.
        assertThat(statusesSinceNoted).isNotEmpty().allMatch(status -> status == 302);
        assertThat(notedRow).isNotNull().isNotEmpty();
        assertThat(data.rowState(code)).isEqualTo(notedRow);
    }
}
