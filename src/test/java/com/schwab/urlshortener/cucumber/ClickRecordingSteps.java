package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ApiClient;
import com.schwab.urlshortener.support.ShortUrlTestData;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import com.schwab.urlshortener.support.TestClock;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * Steps for {@code click-recording.feature} (US-010). Real HTTP; the clock is the controllable {@link TestClock}
 * (reset by {@link TestClockHooks}); a real database failure is injected with a PL/pgSQL trigger that is dropped
 * before and after every scenario so it can never leak into another.
 */
public class ClickRecordingSteps {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;

    private ApiClient client;
    private ShortUrlTestData data;
    private LifecycleState remembered;
    private List<HttpResponse<String>> simultaneous = new ArrayList<>();

    private ApiClient client() {
        if (client == null) {
            client = new ApiClient(port, objectMapper);
            data = new ShortUrlTestData(jdbc);
        }
        return client;
    }

    @Before(order = 1)
    public void dropFailureBefore() {
        dropFailure();
    }

    @After(order = 1)
    public void dropFailureAfter() {
        dropFailure();
    }

    private void dropFailure() {
        client();
        data.dropClickFailures();
    }

    // ---- Given / When ----

    @Given("the clock is fixed at {string}")
    public void theClockIsFixedAt(String instant) {
        clock.setInstant(Instant.parse(instant));
    }

    @When("the clock advances by {int} seconds")
    public void theClockAdvances(int seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    @Given("the stored row of {string} is remembered for click recording")
    public void theStoredRowIsRemembered(String code) {
        client();
        remembered = data.lifecycleState(code);
    }

    @Given("the database rejects every click event insert")
    public void theDatabaseRejectsEveryClickEventInsert() {
        dropFailure();
        client();
        data.failClickInserts();
    }

    @Given("the database rejects every click counter update")
    public void theDatabaseRejectsEveryClickCounterUpdate() {
        dropFailure();
        client();
        data.failClickUpdates();
    }

    @When("the injected click failure is removed")
    public void theInjectedClickFailureIsRemoved() {
        dropFailure();
    }

    @When("{int} anonymous visitors follow the short link {string} at the same time")
    public void manyVisitorsFollow(int visitors, String code) throws Exception {
        client();
        CyclicBarrier barrier = new CyclicBarrier(visitors);
        ExecutorService pool = Executors.newFixedThreadPool(visitors);
        try {
            List<Callable<HttpResponse<String>>> attempts = new ArrayList<>();
            for (int i = 0; i < visitors; i++) {
                attempts.add(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    return client.send("GET", "/" + code, null, null, null);
                });
            }
            simultaneous = new ArrayList<>();
            for (Future<HttpResponse<String>> future : pool.invokeAll(attempts, 60, TimeUnit.SECONDS)) {
                simultaneous.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Then ----

    @Then("the stored click events of {string} are exactly at {string}")
    public void theStoredClickEventsAreExactlyAt(String code, String instants) {
        client();
        List<Instant> expected = Arrays.stream(instants.split(",\\s*")).map(Instant::parse).toList();
        assertThat(data.clickedAts(code)).containsExactlyElementsOf(expected);
    }

    @Then("the stored click events of {string} number {int}")
    public void theStoredClickEventsNumber(String code, int count) {
        client();
        assertThat(data.clickEventCount(code)).isEqualTo(count);
    }

    @Then("the stored click count of {string} is {int}")
    public void theStoredClickCountIs(String code, int count) {
        client();
        assertThat(data.lifecycleState(code).clickCount()).isEqualTo(count);
    }

    @Then("the stored version and update time of {string} are unchanged for click recording")
    public void versionAndUpdatedAtUnchanged(String code) {
        assertThat(remembered).as("the row was remembered before the click").isNotNull();
        LifecycleState now = data.lifecycleState(code);
        assertThat(now.clickCount()).as("non-vacuity: the click was written").isGreaterThan(remembered.clickCount());
        assertThat(now.version()).isEqualTo(remembered.version());
        assertThat(now.updatedAt()).isEqualTo(remembered.updatedAt());
    }

    @Then("all simultaneous visitors were redirected to {string}")
    public void allSimultaneousVisitorsWereRedirected(String location) {
        assertThat(simultaneous).isNotEmpty();
        assertThat(simultaneous).allSatisfy(r -> {
            assertThat(r.statusCode()).isEqualTo(302);
            assertThat(r.headers().allValues("Location")).containsExactly(location);
        });
    }
}
