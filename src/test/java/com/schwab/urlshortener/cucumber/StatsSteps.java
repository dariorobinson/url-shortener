package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schwab.urlshortener.model.StatsPeriod;
import com.schwab.urlshortener.support.ApiClient;
import com.schwab.urlshortener.support.ShortUrlTestData;
import com.schwab.urlshortener.support.TestUsers;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Steps for {@code stats.feature} (US-011). Real HTTP. Query strings are sent exactly as written in the
 * feature, so a {@code +} must be written as {@code %2B} (D96). Click events are arranged with raw SQL bound as
 * UTC offset date-times (see {@link ShortUrlTestData#seedClickEvents}).
 */
public class StatsSteps {

    private static final String BASE = "/api/v1/urls/";
    private static final Set<String> STATS_KEYS = Set.of("shortCode", "timezone", "from", "to", "totalClicks",
            "clicksInRange", "lastAccessedAt", "daily",
            "expiresAt", "expired");
    private static final Set<String> PROBLEM_KEYS = Set.of("type", "title", "status", "detail", "instance",
            "errorCode");
    private static final List<String> FIXED_TEXTS = List.of(StatsPeriod.TIMEZONE_RULE, StatsPeriod.DATE_RULE,
            StatsPeriod.ORDER_RULE, StatsPeriod.LENGTH_RULE);

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

    private ApiClient client() {
        if (client == null) {
            client = new ApiClient(port, objectMapper);
            data = new ShortUrlTestData(jdbc);
        }
        return client;
    }

    private JsonNode body() throws Exception {
        return client().json(response);
    }

    private static String path(String code, String query) {
        return BASE + code + "/stats" + (query == null ? "" : "?" + query);
    }

    // ---- Given ----

    @Given("the short URL {string} has clicks at {string}")
    public void theShortUrlHasClicksAt(String code, String instants) {
        client();
        data.seedClickEvents(code, Arrays.stream(instants.split(",\\s*")).map(Instant::parse).toArray(Instant[]::new));
    }

    // ---- When ----

    @When("{word} requests the stats of {string}")
    public void requestsTheStatsOf(String user, String code) throws Exception {
        response = client().send("GET", path(code, null), TestUsers.require(user), null, null);
    }

    @When("{word} requests the stats of {string} with query {string}")
    public void requestsTheStatsWithQuery(String user, String code, String query) throws Exception {
        response = client().send("GET", path(code, query), TestUsers.require(user), null, null);
    }

    @When("an anonymous caller requests the stats of {string}")
    public void anAnonymousCallerRequestsTheStatsOf(String code) throws Exception {
        response = client().send("GET", path(code, null), null, null, null);
    }

    @When("{word} requests the stats of {string} with a wrong password")
    public void requestsTheStatsWithAWrongPassword(String user, String code) throws Exception {
        String token = Base64.getEncoder()
                .encodeToString((TestUsers.require(user) + ":not-the-password").getBytes(StandardCharsets.UTF_8));
        response = client().send("GET", path(code, null), null, null, null, "Authorization", "Basic " + token);
    }

    @When("{word} sends HEAD for the stats of {string}")
    public void sendsHeadForTheStatsOf(String user, String code) throws Exception {
        response = client().send("HEAD", path(code, null), TestUsers.require(user), null, null);
    }

    @When("the stats response is remembered")
    public void theStatsResponseIsRemembered() {
        remembered = response;
    }

    // ---- Then: status and errors ----

    @Then("the stats response status is {int}")
    public void theStatsResponseStatusIs(int status) {
        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Then("the stats response has error code {string}")
    public void theStatsResponseHasErrorCode(String errorCode) throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        assertThat(body().path("errorCode").asText()).isEqualTo(errorCode);
    }

    @Then("the stats response has a validation error on only {string}")
    public void hasAValidationErrorOnOnly(String field) throws Exception {
        JsonNode errors = body().path("errors");
        assertThat(errors.isArray()).isTrue();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).path("field").asText()).isEqualTo(field);
        assertThat(FIXED_TEXTS).contains(errors.get(0).path("message").asText());
    }

    @Then("the stats response has no validation errors")
    public void hasNoValidationErrors() throws Exception {
        assertThat(ApiClient.keys(body())).isEqualTo(PROBLEM_KEYS);
    }

    @Then("the stats response does not echo {string}")
    public void doesNotEcho(String value) {
        String text = response.body();
        for (String fixed : FIXED_TEXTS) {
            text = text.replace(fixed, "");
        }
        assertThat(text).doesNotContain("\"" + value + "\"");
        if (value.length() >= 3) {
            assertThat(text).doesNotContain(value);
        }
    }

    @Then("the stats response reveals nothing about the short URL")
    public void revealsNothing() {
        assertThat(response.body()).doesNotContain("example.com").doesNotContain("alice").doesNotContain("totalClicks")
                .doesNotContain("daily").doesNotContain("ACTIVE");
    }

    @Then("the stats response equals the 404 for an unknown code apart from the instance")
    public void equalsTheUnknownCode404() throws Exception {
        HttpResponse<String> unknown = client().send("GET", path("Nothing9", "timezone=UTC"), TestUsers.BOB, null,
                null);
        assertThat(unknown.statusCode()).isEqualTo(404);
        ObjectNode expected = ((ObjectNode) client().json(unknown)).deepCopy();
        ObjectNode actual = ((ObjectNode) body()).deepCopy();
        expected.remove("instance");
        actual.remove("instance");
        assertThat(actual).isEqualTo(expected);
        assertThat(ApiClient.contentType(response)).isEqualTo(ApiClient.contentType(unknown));
    }

    @Then("the stats response has a Basic challenge")
    public void hasABasicChallenge() {
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("Basic"));
    }

    @Then("the stats response must not be cached")
    public void mustNotBeCached() {
        assertThat(response.headers().firstValue("Cache-Control"))
                .contains("no-cache, no-store, max-age=0, must-revalidate");
    }

    @Then("the stats response has no body")
    public void hasNoBody() {
        assertThat(response.body()).isEmpty();
    }

    @Then("the stats response headers equal the remembered ones apart from the framing")
    public void headersEqualTheRememberedOnes() {
        assertThat(remembered.statusCode()).isEqualTo(200);
        assertThat(remembered.body()).isNotEmpty();
        assertThat(ApiClient.headersExceptFraming(response)).isEqualTo(ApiClient.headersExceptFraming(remembered));
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
    }

    // ---- Then: the 200 body ----

    /** The D101 key set, the daily entry key set, and clicksInRange equal to the sum of daily. */
    @Then("the stats response has exactly the documented fields")
    public void hasExactlyTheDocumentedFields() throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/json");
        JsonNode stats = body();
        assertThat(ApiClient.keys(stats)).isEqualTo(STATS_KEYS);
        long sum = 0;
        LocalDate expected = LocalDate.parse(stats.path("from").asText());
        for (JsonNode day : stats.path("daily")) {
            assertThat(ApiClient.keys(day)).containsExactly("clicks", "date");
            assertThat(LocalDate.parse(day.path("date").asText())).isEqualTo(expected);
            expected = expected.plusDays(1);
            sum += day.path("clicks").asLong();
        }
        assertThat(expected.minusDays(1)).isEqualTo(LocalDate.parse(stats.path("to").asText()));
        assertThat(stats.path("clicksInRange").asLong()).isEqualTo(sum);
    }

    @Then("the stats response echoes timezone {string} from {string} and to {string}")
    public void echoes(String timezone, String from, String to) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body().path("timezone").asText()).isEqualTo(timezone);
        assertThat(body().path("from").asText()).isEqualTo(from);
        assertThat(body().path("to").asText()).isEqualTo(to);
    }

    @Then("the stats response timezone is {string}")
    public void timezoneIs(String timezone) throws Exception {
        assertThat(body().path("timezone").asText()).isEqualTo(timezone);
    }

    @Then("the stats response has {int} daily entries")
    public void hasDailyEntries(int count) throws Exception {
        assertThat(body().path("daily")).hasSize(count);
        hasExactlyTheDocumentedFields();
    }

    /** Counts in order, and that the dates are consecutive from {@code from} to {@code to}. */
    @Then("the stats response daily counts are {string}")
    public void dailyCountsAre(String counts) throws Exception {
        List<Long> expected = Arrays.stream(counts.split(",\\s*")).map(Long::parseLong).toList();
        List<Long> actual = new ArrayList<>();
        body().path("daily").forEach(d -> actual.add(d.path("clicks").asLong(-1)));
        assertThat(actual).containsExactlyElementsOf(expected);
        hasExactlyTheDocumentedFields();
    }

    @Then("the stats response shows {int} total clicks and {int} clicks in range")
    public void showsTotals(int total, int inRange) throws Exception {
        assertThat(body().path("totalClicks").asLong(-1)).isEqualTo(total);
        assertThat(body().path("clicksInRange").asLong(-1)).isEqualTo(inRange);
    }

    @Then("the stats response shows the last access at {string}")
    public void showsLastAccess(String instant) throws Exception {
        assertThat(Instant.parse(body().path("lastAccessedAt").asText())).isEqualTo(Instant.parse(instant));
    }

    @Then("the stats response shows no last access")
    public void showsNoLastAccess() throws Exception {
        assertThat(body().has("lastAccessedAt")).isTrue();
        assertThat(body().get("lastAccessedAt").isNull()).isTrue();
    }
}
