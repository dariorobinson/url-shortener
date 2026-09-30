package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-009 AC11, D35, D86 and D87: optimistic locking on PATCH and DELETE.
 *
 * <p>(a) Two PATCH requests racing behind a barrier, repeated: exactly one 200, one 409 whose errorCode is
 * either CONCURRENT_MODIFICATION (the requests overlapped) or SHORT_URL_ALREADY_DEACTIVATED (they
 * serialised), and version N+1. Which code lands is timing and is recorded, not asserted.
 *
 * <p>(b) Deterministic overlap: a separate JDBC connection holds an uncommitted UPDATE of the row, the request
 * has already read the committed version N and provably blocks on the row lock at its versioned UPDATE
 * (observed in pg_stat_activity, not assumed from elapsed time). Committing the holder's version bump makes
 * the request's {@code WHERE version = N} match no row: 409 CONCURRENT_MODIFICATION. Rolling it back lets the
 * request through: 200. The rollback twin proves the 409 comes from the version predicate at UPDATE time and
 * not from an application pre-check. Nothing sleeps: every wait is a condition poll with a deadline.
 *
 * <p>(c) Serialised requests, DELETE against PATCH (D87), and a click-shaped UPDATE that must neither cause a
 * 409 nor be overwritten (D27).
 */
@ExtendWith(OutputCaptureExtension.class)
class LifecycleConcurrencyIT extends IntegrationTestBase {

    private static final String BASE = "/api/v1/urls/";
    private static final String JSON = "application/json";
    private static final String INACTIVE_BODY = "{\"active\": false}";
    private static final String ACTIVE_BODY = "{\"active\": true}";
    private static final String CONFLICT = "CONCURRENT_MODIFICATION";
    private static final String ALREADY_DEACTIVATED = "SHORT_URL_ALREADY_DEACTIVATED";
    private static final Set<String> PROBLEM_KEYS =
            Set.of("type", "title", "status", "detail", "instance", "errorCode");
    private static final int RACE_ROUNDS = 10;
    private static final Instant SEEDED_LAST_ACCESS = Instant.parse("2026-03-01T10:15:30Z");
    private static final String CONFLICT_LOG = "Short URL changed concurrently: code=";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private ApiClient api;
    private ShortUrlTestData data;
    private ExecutorService requests;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        requests = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        requests.shutdownNow();
    }

    // ---- helpers ----

    private String createAlice(String code) throws Exception {
        HttpResponse<String> created = api.post(TestUsers.ALICE,
                api.createBody("https://example.com/concurrency/" + code, code));
        assertThat(created.statusCode()).as("create %s", code).isEqualTo(201);
        return code;
    }

    private HttpResponse<String> patch(String user, String code, String body) throws Exception {
        return api.send("PATCH", BASE + code, user, JSON, body);
    }

    private HttpResponse<String> delete(String user, String code) throws Exception {
        return api.send("DELETE", BASE + code, user, null, null);
    }

    /** Sends on a background thread, so the test thread can poll the database while the request blocks. */
    private CompletableFuture<HttpResponse<String>> patchAsync(String user, String code, String body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return patch(user, code, body);
            } catch (Exception e) {
                throw new IllegalStateException("request failed", e);
            }
        }, requests);
    }

    private CompletableFuture<HttpResponse<String>> deleteAsync(String user, String code) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delete(user, code);
            } catch (Exception e) {
                throw new IllegalStateException("request failed", e);
            }
        }, requests);
    }

    private HttpResponse<String> await(CompletableFuture<HttpResponse<String>> request) throws Exception {
        return request.get(30, TimeUnit.SECONDS);
    }

    private void assertConflict(HttpResponse<String> response, String errorCode, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(ApiClient.contentType(response)).startsWith("application/problem+json");
        JsonNode body = api.json(response);
        assertThat(ApiClient.keys(body)).isEqualTo(PROBLEM_KEYS);
        assertThat(body.path("errorCode").asText()).isEqualTo(errorCode);
        assertThat(body.path("instance").asText()).isEqualTo(BASE + code);
    }

    private void assertNotFound(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(api.json(response).path("errorCode").asText()).isEqualTo("SHORT_URL_NOT_FOUND");
    }

    private Connection openHolder() throws SQLException {
        Connection holder = dataSource.getConnection();
        holder.setAutoCommit(false);
        return holder;
    }

    /** The winner of a race: bumps the version, as a committed PATCH would. Not committed here. */
    private static void holdDeactivate(Connection holder, String code) throws SQLException {
        execute(holder, "UPDATE short_url SET status = 'DEACTIVATED', updated_at = now(), version = version + 1"
                + " WHERE short_code = ?", code);
    }

    /** A committed soft delete: the three D44 columns together, with the version bump. */
    private static void holdSoftDelete(Connection holder, String code) throws SQLException {
        execute(holder, "UPDATE short_url SET status = 'DELETED', deleted_at = now(), deleted_by = 'admin',"
                + " updated_at = now(), version = version + 1 WHERE short_code = ?", code);
    }

    /** A click-shaped write: leaves version and updated_at alone (D27). */
    private static void holdClick(Connection holder, String code) throws SQLException {
        try (PreparedStatement ps = holder.prepareStatement("UPDATE short_url SET click_count = click_count + 1,"
                + " last_accessed_at = ? WHERE short_code = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.parse("2026-04-01T00:00:00Z")));
            ps.setString(2, code);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static void execute(Connection holder, String sql, String code) throws SQLException {
        try (PreparedStatement ps = holder.prepareStatement(sql)) {
            ps.setString(1, code);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Polls pg_stat_activity until another backend's UPDATE of short_url waits on a lock. That statement is the
     * request's versioned UPDATE, so its SELECT has already read the committed row. Returns the waiting query
     * text so the caller can check that it carries the version predicate.
     */
    private String awaitRequestBlockedOnRowLock() {
        return Awaitility.await("request blocked on the row lock at its UPDATE")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> jdbc.queryForList("SELECT query FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'"
                        + " AND query ILIKE 'update short_url%'", String.class),
                        waiting -> !waiting.isEmpty())
                .get(0);
    }

    private void assertNoErrorNoise(String logs) {
        assertThat(logs).doesNotContain("HHH000346").doesNotContain(" ERROR ")
                .doesNotContain("StaleObjectStateException")
                .doesNotContain("ObjectOptimisticLockingFailureException").doesNotContain("\tat ");
    }

    // ---- AC11: the race ----

    @Test
    void shouldAnswerExactlyOne200AndOne409WhenTwoDeactivationsRaceAndBumpTheVersionOnce(CapturedOutput output)
            throws Exception {
        Map<String, Integer> distribution = new TreeMap<>();
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String code = createAlice("Race" + round + "Code");
            long versionBefore = data.lifecycleState(code).version();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<HttpResponse<String>> attempt = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return patch(TestUsers.ALICE, code, INACTIVE_BODY);
            };

            List<HttpResponse<String>> results = new ArrayList<>();
            for (Future<HttpResponse<String>> future : requests.invokeAll(List.of(attempt, attempt), 30,
                    TimeUnit.SECONDS)) {
                results.add(future.get());
            }

            assertThat(results.stream().map(HttpResponse::statusCode).sorted().toList())
                    .as("round %d statuses", round).containsExactly(200, 409);
            HttpResponse<String> winner = results.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
            HttpResponse<String> loser = results.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
            assertThat(api.json(winner).path("status").asText()).isEqualTo("DEACTIVATED");
            JsonNode loserBody = api.json(loser);
            assertThat(ApiClient.keys(loserBody)).isEqualTo(PROBLEM_KEYS);
            assertThat(loserBody.path("errorCode").asText()).as("round %d loser", round)
                    .isIn(CONFLICT, ALREADY_DEACTIVATED);
            LifecycleState after = data.lifecycleState(code);
            assertThat(after.status()).isEqualTo("DEACTIVATED");
            assertThat(after.version()).as("round %d: exactly one write", round).isEqualTo(versionBefore + 1);
            distribution.merge(loserBody.path("errorCode").asText(), 1, Integer::sum);
        }

        // Recorded for the QA notes; deliberately not asserted (D86).
        Files.writeString(Path.of("target", "lifecycle-race-distribution.txt"),
                distribution.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
        String logs = output.getAll();
        assertThat(logs).contains("Short URL deactivated: code=Race0Code");
        assertNoErrorNoise(logs);
    }

    // ---- D86: forced overlap, commit and rollback ----

    @Test
    void shouldReturn409ConcurrentModificationWhenTheHolderCommitsAVersionBumpUnderABlockedPatch(
            CapturedOutput output) throws Exception {
        String code = createAlice("Lock0001");
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            holdDeactivate(holder, code);
            CompletableFuture<HttpResponse<String>> request = patchAsync(TestUsers.ALICE, code, INACTIVE_BODY);
            String blocked = awaitRequestBlockedOnRowLock();
            assertThat(blocked).as("the blocked statement is the versioned UPDATE").containsIgnoringCase("version");
            assertThat(request).as("the request waits for the row lock, it is not finished").isNotDone();

            holder.commit();

            HttpResponse<String> response = await(request);
            assertConflict(response, CONFLICT, code);
            assertThat(api.json(response).has("errors")).isFalse();
        }
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DEACTIVATED");
        assertThat(after.version()).as("only the holder's write landed").isEqualTo(seeded.version() + 1);
        String logs = output.getAll();
        assertThat(logs).as("positive control: the expected conflict is logged at INFO").contains(CONFLICT_LOG + code)
                .contains("action=DEACTIVATE");
        assertNoErrorNoise(logs);
    }

    @Test
    void shouldReturn200WhenTheHolderRollsBackSoTheConflictCameFromTheVersionPredicateAtUpdateTime()
            throws Exception {
        String code = createAlice("Lock0002");
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            holdDeactivate(holder, code);
            CompletableFuture<HttpResponse<String>> request = patchAsync(TestUsers.ALICE, code, INACTIVE_BODY);
            awaitRequestBlockedOnRowLock();
            assertThat(request).isNotDone();

            holder.rollback();

            HttpResponse<String> response = await(request);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(api.json(response).path("status").asText()).isEqualTo("DEACTIVATED");
        }
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DEACTIVATED");
        assertThat(after.version()).isEqualTo(seeded.version() + 1);
    }

    @Test
    void shouldReturn409ConcurrentModificationForAReactivationOverlappingAChange(CapturedOutput output)
            throws Exception {
        String code = createAlice("Lock0003");
        assertThat(patch(TestUsers.ALICE, code, INACTIVE_BODY).statusCode()).isEqualTo(200);
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            // A racing writer reactivates the link first: the version moves past the one the request read.
            execute(holder, "UPDATE short_url SET status = 'ACTIVE', updated_at = now(), version = version + 1"
                    + " WHERE short_code = ?", code);
            CompletableFuture<HttpResponse<String>> request = patchAsync(TestUsers.ALICE, code, ACTIVE_BODY);
            awaitRequestBlockedOnRowLock();
            holder.commit();

            assertConflict(await(request), CONFLICT, code);
        }
        assertThat(data.lifecycleState(code).version()).isEqualTo(seeded.version() + 1);
        assertThat(output.getAll()).contains(CONFLICT_LOG + code).contains("action=REACTIVATE");
    }

    // ---- serialised ----

    @Test
    void shouldAnswer409AlreadyDeactivatedWhenTheSecondDeactivationRunsAfterTheFirstCommitted(CapturedOutput output)
            throws Exception {
        String code = createAlice("Serial01");
        LifecycleState seeded = data.lifecycleState(code);

        HttpResponse<String> first = patch(TestUsers.ALICE, code, INACTIVE_BODY);
        HttpResponse<String> second = patch(TestUsers.ALICE, code, INACTIVE_BODY);

        assertThat(first.statusCode()).isEqualTo(200);
        assertConflict(second, ALREADY_DEACTIVATED, code);
        assertThat(data.lifecycleState(code).version()).isEqualTo(seeded.version() + 1);
        assertThat(output.getAll()).doesNotContain(CONFLICT_LOG);
    }

    // ---- D87: DELETE against PATCH ----

    @Test
    void shouldReturn409ForAPatchThatLosesToACommittedDeleteAndThen404OnReread(CapturedOutput output)
            throws Exception {
        String code = createAlice("DelPatch");
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            holdSoftDelete(holder, code);
            CompletableFuture<HttpResponse<String>> request = patchAsync(TestUsers.ALICE, code, INACTIVE_BODY);
            awaitRequestBlockedOnRowLock();
            holder.commit();

            assertConflict(await(request), CONFLICT, code);
        }
        assertNotFound(patch(TestUsers.ALICE, code, INACTIVE_BODY));
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DELETED");
        assertThat(after.version()).isEqualTo(seeded.version() + 1);
        assertThat(output.getAll()).contains(CONFLICT_LOG + code);
        assertNoErrorNoise(output.getAll());
    }

    @Test
    void shouldReturn409ForAnAdminDeleteThatLosesToACommittedDeactivationAndLeaveTheRowNotDeleted(
            CapturedOutput output) throws Exception {
        String code = createAlice("PatchDel");
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            holdDeactivate(holder, code);
            CompletableFuture<HttpResponse<String>> request = deleteAsync(TestUsers.ADMIN, code);
            awaitRequestBlockedOnRowLock();
            holder.commit();

            assertConflict(await(request), CONFLICT, code);
        }
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DEACTIVATED");
        assertThat(after.deletedAt()).isNull();
        assertThat(after.deletedBy()).isNull();
        assertThat(after.version()).isEqualTo(seeded.version() + 1);
        // Re-reading and retrying now succeeds: deleting a DEACTIVATED link is allowed (D36).
        assertThat(delete(TestUsers.ADMIN, code).statusCode()).isEqualTo(204);
        assertThat(data.lifecycleState(code).status()).isEqualTo("DELETED");
        assertThat(output.getAll()).contains(CONFLICT_LOG + code).contains("action=DELETE");
        assertNoErrorNoise(output.getAll());
    }

    @Test
    void shouldReturn409ForADeleteThatLosesToACommittedDeleteAndThen404OnReread() throws Exception {
        String code = createAlice("DelDel01");

        try (Connection holder = openHolder()) {
            holdSoftDelete(holder, code);
            CompletableFuture<HttpResponse<String>> request = deleteAsync(TestUsers.ADMIN, code);
            awaitRequestBlockedOnRowLock();
            holder.commit();

            assertConflict(await(request), CONFLICT, code);
        }
        assertNotFound(delete(TestUsers.ADMIN, code));
        assertThat(data.lifecycleState(code).status()).isEqualTo("DELETED");
    }

    @Test
    void shouldSucceedForADeleteWhoseBlockingWriterRollsBack() throws Exception {
        String code = createAlice("DelRoll1");
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            holdDeactivate(holder, code);
            CompletableFuture<HttpResponse<String>> request = deleteAsync(TestUsers.ADMIN, code);
            awaitRequestBlockedOnRowLock();
            holder.rollback();

            assertThat(await(request).statusCode()).isEqualTo(204);
        }
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DELETED");
        assertThat(after.version()).isEqualTo(seeded.version() + 1);
    }

    @Test
    void shouldSerialiseDeleteAfterPatchAndPatchAfterDelete() throws Exception {
        String code = createAlice("Serial02");
        LifecycleState seeded = data.lifecycleState(code);

        assertThat(patch(TestUsers.ALICE, code, INACTIVE_BODY).statusCode()).isEqualTo(200);
        assertThat(delete(TestUsers.ADMIN, code).statusCode()).as("D36: a DEACTIVATED link may be deleted")
                .isEqualTo(204);
        assertNotFound(patch(TestUsers.ALICE, code, ACTIVE_BODY));
        assertNotFound(patch(TestUsers.ADMIN, code, INACTIVE_BODY));
        assertNotFound(delete(TestUsers.ADMIN, code));

        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DELETED");
        assertThat(after.version()).isEqualTo(seeded.version() + 2);
    }

    // ---- D27: clicks and lifecycle changes do not disturb one another ----

    @Test
    void shouldNeitherFailNorOverwriteAClickThatCommitsWhileAPatchWaitsForTheRowLock(CapturedOutput output)
            throws Exception {
        String code = createAlice("Click001");
        data.seedClicks(code, 7, SEEDED_LAST_ACCESS);
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            holdClick(holder, code);
            CompletableFuture<HttpResponse<String>> request = patchAsync(TestUsers.ALICE, code, INACTIVE_BODY);
            awaitRequestBlockedOnRowLock();
            assertThat(request).isNotDone();

            holder.commit();

            HttpResponse<String> response = await(request);
            assertThat(response.statusCode()).as("a click never causes a 409").isEqualTo(200);
            JsonNode body = api.json(response);
            assertThat(body.path("status").asText()).isEqualTo("DEACTIVATED");
            // D90: the body shows the click data as read inside the PATCH transaction, before the click.
            assertThat(body.path("clickCount").asLong(-1)).isEqualTo(7);
        }
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DEACTIVATED");
        assertThat(after.version()).as("the PATCH write happened").isEqualTo(seeded.version() + 1);
        assertThat(after.clickCount()).as("the click survived the PATCH").isEqualTo(8);
        assertThat(after.lastAccessedAt()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        JsonNode next = api.json(api.send("GET", BASE + code, TestUsers.ALICE, null, null));
        assertThat(next.path("clickCount").asLong(-1)).as("D90: the next GET shows the click").isEqualTo(8);
        assertThat(output.getAll()).doesNotContain(CONFLICT_LOG);
    }

    @Test
    void shouldNeitherFailNorOverwriteAClickThatCommitsWhileAnAdminDeleteWaitsForTheRowLock() throws Exception {
        String code = createAlice("Click002");
        data.seedClicks(code, 3, SEEDED_LAST_ACCESS);
        LifecycleState seeded = data.lifecycleState(code);

        try (Connection holder = openHolder()) {
            holdClick(holder, code);
            CompletableFuture<HttpResponse<String>> request = deleteAsync(TestUsers.ADMIN, code);
            awaitRequestBlockedOnRowLock();
            holder.commit();

            assertThat(await(request).statusCode()).isEqualTo(204);
        }
        LifecycleState after = data.lifecycleState(code);
        assertThat(after.status()).isEqualTo("DELETED");
        assertThat(after.version()).isEqualTo(seeded.version() + 1);
        assertThat(after.clickCount()).isEqualTo(4);
        assertThat(after.lastAccessedAt()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
    }
}
