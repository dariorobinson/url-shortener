package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-010 AC4, D16, D27, D90 and D91 under concurrency. Nothing sleeps: threads meet at barriers and latches, and
 * every wait on the database is a condition poll on pg_stat_activity with a deadline.
 *
 * <p>AC4: 50 GETs released together by a barrier give exactly 50 clicks and 50 events.
 * The PATCH race: the real recordClick statement is held inside a test-owned transaction while a PATCH waits on
 * the same row lock; the PATCH must still succeed (200, never 409) and the click must survive.
 * The state race: a raw deactivation or soft delete is held while a GET has already resolved the link as ACTIVE
 * and waits at the click UPDATE; once the holder commits the redirect is still served but nothing is counted
 * (D91), and a rollback twin shows the same GET is counted when the link stays ACTIVE.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClickRecordingConcurrencyIT extends IntegrationTestBase {

    private static final String CODE = "Busy0001";
    private static final String TARGET = "https://example.com/busy";
    private static final int VISITORS = 50;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ShortUrlRepository shortUrls;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ApiClient api;
    private ShortUrlTestData data;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private CompletableFuture<HttpResponse<String>> async(String method, String path, String user, String type,
            String body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return api.send(method, path, user, type, body);
            } catch (Exception e) {
                throw new IllegalStateException("request failed", e);
            }
        }, pool);
    }

    private HttpResponse<String> await(CompletableFuture<HttpResponse<String>> request) throws Exception {
        return request.get(30, TimeUnit.SECONDS);
    }

    /** Polls pg_stat_activity until another backend's UPDATE matching {@code pattern} waits on a lock. */
    private void awaitBlocked(String pattern) {
        Awaitility.await("statement blocked on the row lock: " + pattern).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> jdbc.queryForList("SELECT query FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query ILIKE ?",
                        String.class, pattern), waiting -> !waiting.isEmpty());
    }

    private Connection openHolder() throws SQLException {
        Connection holder = dataSource.getConnection();
        holder.setAutoCommit(false);
        return holder;
    }

    private static void holdStateChange(Connection holder, String sql) throws SQLException {
        try (PreparedStatement ps = holder.prepareStatement(sql)) {
            ps.setString(1, CODE);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static final String DEACTIVATE = "UPDATE short_url SET status = 'DEACTIVATED', updated_at = now(),"
            + " version = version + 1 WHERE short_code = ?";
    private static final String SOFT_DELETE = "UPDATE short_url SET status = 'DELETED', deleted_at = now(),"
            + " deleted_by = 'admin', updated_at = now(), version = version + 1 WHERE short_code = ?";
    private static final String CLICK_UPDATE_PATTERN = "update short_url set click_count%";

    // ---- AC4 ----

    @Test
    void shouldCountExactlyFiftyClicksAndFiftyEventsForFiftyConcurrentGets(CapturedOutput output) throws Exception {
        data.seed(CODE, "ACTIVE", TARGET);
        LifecycleState before = data.lifecycleState(CODE);
        CyclicBarrier barrier = new CyclicBarrier(VISITORS);
        ExecutorService visitors = Executors.newFixedThreadPool(VISITORS);
        try {
            Callable<HttpResponse<String>> visit = () -> {
                barrier.await(20, TimeUnit.SECONDS);
                return api.send("GET", "/" + CODE, null, null, null);
            };
            List<Callable<HttpResponse<String>>> visits = new ArrayList<>();
            for (int i = 0; i < VISITORS; i++) {
                visits.add(visit);
            }
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : visitors.invokeAll(visits, 60, TimeUnit.SECONDS)) {
                responses.add(future.get());
            }

            assertThat(responses).hasSize(VISITORS).allSatisfy(response -> {
                assertThat(response.statusCode()).isEqualTo(302);
                assertThat(response.headers().allValues("Location")).containsExactly(TARGET);
                assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
            });
        } finally {
            visitors.shutdownNow();
        }

        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.clickCount()).isEqualTo(VISITORS);
        assertThat(data.clickEventCount(CODE)).isEqualTo(VISITORS);
        assertThat(data.clickEventCount()).isEqualTo(VISITORS);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(after.lastAccessedAt()).isNotNull();
        assertThat(output.getAll()).doesNotContain("Click not recorded");
    }

    // ---- PATCH racing a held click (D16, D27) ----

    @Test
    void shouldAnswer200AndKeepTheClickWhenAPatchWaitsBehindTheRealClickStatement(CapturedOutput output)
            throws Exception {
        data.seed(CODE, "ACTIVE", TARGET);
        LifecycleState seeded = data.lifecycleState(CODE);
        long id = data.shortUrlId(CODE);
        CountDownLatch clickApplied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        CompletableFuture<Integer> holder = CompletableFuture.supplyAsync(() -> template.execute(status -> {
            int changed = shortUrls.recordClick(id, Instant.parse("2026-03-01T10:15:30.123456Z"));
            clickApplied.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("holder never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return changed;
        }), pool);
        assertThat(clickApplied.await(20, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<HttpResponse<String>> patch = async("PATCH", "/api/v1/urls/" + CODE, TestUsers.ADMIN,
                "application/json", "{\"active\": false}");
        awaitBlocked("update short_url%version%");
        assertThat(patch).as("the PATCH waits for the click's row lock").isNotDone();

        release.countDown();

        assertThat(holder.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        HttpResponse<String> response = await(patch);
        assertThat(response.statusCode()).as("never 409: a click does not bump version").isEqualTo(200);
        assertThat(api.json(response).path("status").asText()).isEqualTo("DEACTIVATED");
        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.status()).isEqualTo("DEACTIVATED");
        assertThat(after.version()).isEqualTo(seeded.version() + 1);
        assertThat(after.clickCount()).as("the click survives the PATCH (D27)").isEqualTo(1);
        assertThat(after.lastAccessedAt()).isEqualTo(Instant.parse("2026-03-01T10:15:30.123456Z"));
        assertThat(output.getAll()).doesNotContain("Short URL changed concurrently");
    }

    // ---- The state race (D91) ----

    private void raceAClickAgainstAStateChange(String stateChange, boolean commit) throws Exception {
        data.seed(CODE, "ACTIVE", TARGET);
        try (Connection holder = openHolder()) {
            holdStateChange(holder, stateChange);
            CompletableFuture<HttpResponse<String>> get = async("GET", "/" + CODE, null, null, null);
            // The GET has resolved the link as ACTIVE (the holder is uncommitted) and waits at the click UPDATE.
            awaitBlocked(CLICK_UPDATE_PATTERN);
            assertThat(get).as("the GET waits at the click UPDATE").isNotDone();
            if (commit) {
                holder.commit();
            } else {
                holder.rollback();
            }
            HttpResponse<String> response = await(get);
            assertThat(response.statusCode()).as("the decided 302 is still served").isEqualTo(302);
            assertThat(response.headers().allValues("Location")).containsExactly(TARGET);
            assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        }
    }

    @Test
    void shouldServeTheRedirectButCountNothingWhenTheLinkIsDeactivatedWhileTheClickWaits(CapturedOutput output)
            throws Exception {
        raceAClickAgainstAStateChange(DEACTIVATE, true);

        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.status()).isEqualTo("DEACTIVATED");
        assertThat(after.clickCount()).isZero();
        assertThat(after.lastAccessedAt()).isNull();
        assertThat(data.clickEventCount()).isZero();
        assertThat(output.getAll()).as("not a failure: no WARN").doesNotContain("Click not recorded");
    }

    @Test
    void shouldServeTheRedirectButCountNothingWhenTheLinkIsSoftDeletedWhileTheClickWaits(CapturedOutput output)
            throws Exception {
        raceAClickAgainstAStateChange(SOFT_DELETE, true);

        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.status()).isEqualTo("DELETED");
        assertThat(after.clickCount()).isZero();
        assertThat(after.lastAccessedAt()).isNull();
        assertThat(data.clickEventCount()).isZero();
        assertThat(output.getAll()).doesNotContain("Click not recorded");
    }

    @Test
    void shouldCountTheSameRacingClickWhenTheHolderRollsBackSoTheSkipCameFromTheStatusGuard() throws Exception {
        raceAClickAgainstAStateChange(DEACTIVATE, false);

        LifecycleState after = data.lifecycleState(CODE);
        assertThat(after.status()).isEqualTo("ACTIVE");
        assertThat(after.clickCount()).isEqualTo(1);
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
    }
}
