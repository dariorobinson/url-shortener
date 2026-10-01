package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ApiClient;
import com.schwab.urlshortener.support.ShortUrlTestData;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import com.schwab.urlshortener.support.TestUsers;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Steps for {@code short-url-lifecycle.feature} (US-009). Everything goes through real HTTP; rows the API
 * cannot create in a given state are arranged with the shared "owns a short URL" step. Users are resolved
 * through {@link TestUsers#require}, so a typo in a scenario cannot act as nobody. Credentials are never
 * printed.
 */
public class LifecycleSteps {

    private static final String BASE = "/api/v1/urls/";
    private static final String JSON = "application/json";
    private static final Set<String> RESOURCE_KEYS = Set.of("shortCode", "shortUrl", "originalUrl",
            "status", "customAlias", "clickCount", "createdAt", "lastAccessedAt",
            "expiresAt", "expired");
    private static final Set<String> PROBLEM_KEYS =
            Set.of("type", "title", "status", "detail", "instance", "errorCode");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient client;
    private ShortUrlTestData data;
    private HttpResponse<String> response;
    private LifecycleState remembered;
    private String rememberedCode;
    private List<HttpResponse<String>> simultaneous = new ArrayList<>();

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

    private JsonNode body() throws Exception {
        return client().json(response);
    }

    private HttpResponse<String> patch(String user, String code, String body, String... headers) throws Exception {
        return client().send("PATCH", BASE + code, user, JSON, body, headers);
    }

    // ---- When ----

    @When("{word} deactivates the short URL {string}")
    public void deactivates(String user, String code) throws Exception {
        response = patch(TestUsers.require(user), code, "{\"active\": false}");
    }

    @When("{word} reactivates the short URL {string}")
    public void reactivates(String user, String code) throws Exception {
        response = patch(TestUsers.require(user), code, "{\"active\": true}");
    }

    @When("{word} deletes the short URL {string}")
    public void deletes(String user, String code) throws Exception {
        response = client().send("DELETE", BASE + code, TestUsers.require(user), null, null);
    }

    @When("{word} deactivates the short URL {string} accepting {string}")
    public void deactivatesAccepting(String user, String code, String accept) throws Exception {
        response = patch(TestUsers.require(user), code, "{\"active\": false}", "Accept", accept);
    }

    @When("{word} deletes the short URL {string} accepting {string}")
    public void deletesAccepting(String user, String code, String accept) throws Exception {
        response = client().send("DELETE", BASE + code, TestUsers.require(user), null, null, "Accept", accept);
    }

    @When("{word} patches the short URL {string} with content type {string} and body {string}")
    public void patchesWith(String user, String code, String contentType, String body) throws Exception {
        response = client().send("PATCH", BASE + code, TestUsers.require(user), contentType, body);
    }

    @When("an anonymous caller deactivates the short URL {string}")
    public void anonymousDeactivates(String code) throws Exception {
        response = patch(null, code, "{\"active\": false}");
    }

    @When("an anonymous caller deletes the short URL {string}")
    public void anonymousDeletes(String code) throws Exception {
        response = client().send("DELETE", BASE + code, null, null, null);
    }

    @When("{word} sends two simultaneous deactivation requests for the short URL {string}")
    public void sendsTwoSimultaneousDeactivations(String user, String code) throws Exception {
        String caller = TestUsers.require(user);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<HttpResponse<String>> attempt = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return patch(caller, code, "{\"active\": false}");
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            simultaneous = new ArrayList<>();
            for (Future<HttpResponse<String>> future : pool.invokeAll(List.of(attempt, attempt), 30,
                    TimeUnit.SECONDS)) {
                simultaneous.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @When("the stored lifecycle state of {string} is remembered")
    public void remembersTheStoredState(String code) {
        remembered = data().lifecycleState(code);
        rememberedCode = code;
    }

    // ---- Then ----

    @Then("the lifecycle response status is {int}")
    public void statusIs(int status) {
        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Then("the lifecycle response is the short URL {string} with status {string}")
    public void isTheShortUrlWithStatus(String code, String status) throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith(JSON);
        assertThat(ApiClient.keys(body())).isEqualTo(RESOURCE_KEYS);
        assertThat(body().path("shortCode").asText()).isEqualTo(code);
        assertThat(body().path("status").asText()).isEqualTo(status);
    }

    @Then("the lifecycle response has error code {string}")
    public void hasErrorCode(String errorCode) throws Exception {
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        assertThat(ApiClient.keys(body())).containsAll(PROBLEM_KEYS);
        assertThat(body().path("errorCode").asText()).isEqualTo(errorCode);
    }

    @Then("the lifecycle response has no body and no content type")
    public void hasNoBodyAndNoContentType() {
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
    }

    @Then("the lifecycle response shows {int} clicks")
    public void showsClicks(int clicks) throws Exception {
        assertThat(body().path("clickCount").asLong(-1)).isEqualTo(clicks);
    }

    @Then("the lifecycle response has a Basic challenge")
    public void hasABasicChallenge() {
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValueSatisfying(challenge -> assertThat(challenge).startsWith("Basic"));
    }

    @Then("the stored status of {string} is {string}")
    public void storedStatusIs(String code, String status) {
        assertThat(data().lifecycleState(code).status()).isEqualTo(status);
    }

    @Then("the stored lifecycle state of {string} is unchanged")
    public void storedStateIsUnchanged(String code) {
        // Non-vacuity: a state was remembered for this row, and the scenario then makes a successful change to
        // the same row (a version bump), so a request that silently did nothing would be caught there.
        assertThat(remembered).isNotNull();
        assertThat(rememberedCode).isEqualTo(code);
        assertThat(data().lifecycleState(code)).isEqualTo(remembered);
    }

    @Then("the stored version of {string} is {int} higher than remembered")
    public void storedVersionIsHigher(String code, int increase) {
        assertThat(remembered).isNotNull();
        assertThat(rememberedCode).isEqualTo(code);
        assertThat(data().lifecycleState(code).version()).isEqualTo(remembered.version() + increase);
    }

    @Then("the stored short URL {string} was deleted by {string} at the moment it was updated")
    public void storedDeletionAudit(String code, String admin) {
        LifecycleState state = data().lifecycleState(code);
        assertThat(state.deletedBy()).isEqualTo(TestUsers.require(admin));
        assertThat(state.deletedAt()).isNotNull().isEqualTo(state.updatedAt());
    }

    @Then("the stored click data of {string} is {int} clicks, the last at {string}")
    public void storedClickData(String code, int clicks, String lastAccess) {
        LifecycleState state = data().lifecycleState(code);
        assertThat(state.clickCount()).isEqualTo(clicks);
        assertThat(state.lastAccessedAt()).isEqualTo(Instant.parse(lastAccess));
    }

    @Then("exactly one simultaneous request gets 200 and the other gets 409 with a concurrency or "
            + "already-deactivated error code")
    public void exactlyOneWins() throws Exception {
        assertThat(simultaneous.stream().map(HttpResponse::statusCode).sorted().toList())
                .containsExactly(200, 409);
        HttpResponse<String> winner = simultaneous.stream().filter(r -> r.statusCode() == 200).findFirst()
                .orElseThrow();
        HttpResponse<String> loser = simultaneous.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
        assertThat(client().json(winner).path("status").asText()).isEqualTo("DEACTIVATED");
        assertThat(client().json(loser).path("errorCode").asText())
                .isIn("CONCURRENT_MODIFICATION", "SHORT_URL_ALREADY_DEACTIVATED");
    }
}
