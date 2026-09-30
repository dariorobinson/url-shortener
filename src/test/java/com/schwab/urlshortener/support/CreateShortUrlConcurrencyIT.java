package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC9: the database unique constraint, not an application check, decides who wins an alias.
 *
 * <p>(a) Two requests racing behind a barrier, repeated. BCrypt runs before the INSERT, so real
 * overlap at the INSERT is not guaranteed here; this proves the outcome under load.
 *
 * <p>(b) Deterministic overlap: a separate JDBC connection holds an uncommitted INSERT of the
 * alias, the request provably blocks on the row lock (observed in pg_stat_activity, not assumed
 * from elapsed time), then the holder commits (request gets 409) or rolls back (request gets 201).
 * Nothing here sleeps: the wait is a condition poll on database state with a deadline.
 */
class CreateShortUrlConcurrencyIT extends IntegrationTestBase {

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RACE_ROUNDS = 10;

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
    private final SecureRandom random = new SecureRandom();

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.truncate();
    }

    @AfterEach
    void tearDown() {
        data.truncate();
    }

    private String freshAlias() {
        StringBuilder sb = new StringBuilder("Race");
        for (int i = 0; i < 8; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    // ---- (a) racing requests ----

    @Test
    void shouldCreateExactlyOneShortUrlWhenTwoCallersRaceForTheSameAlias() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < RACE_ROUNDS; round++) {
                String alias = freshAlias();
                String body = api.createBody("https://example.com/race/" + alias, alias);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Callable<HttpResponse<String>> alice = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return api.post(TestUsers.ALICE, body);
                };
                Callable<HttpResponse<String>> bob = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return api.post(TestUsers.BOB, body);
                };

                List<Future<HttpResponse<String>>> futures = pool.invokeAll(List.of(alice, bob), 30, TimeUnit.SECONDS);
                List<HttpResponse<String>> results = new ArrayList<>();
                for (Future<HttpResponse<String>> f : futures) {
                    results.add(f.get());
                }

                assertThat(results.stream().map(HttpResponse::statusCode).sorted().toList())
                        .as("round %d statuses", round).containsExactly(201, 409);
                HttpResponse<String> loser = results.stream().filter(r -> r.statusCode() == 409).findFirst()
                        .orElseThrow();
                assertThat(api.json(loser).path("errorCode").asText()).isEqualTo("ALIAS_ALREADY_EXISTS");
                assertThat(data.countByCode(alias)).isEqualTo(1);
                String winner = results.get(0).statusCode() == 201 ? TestUsers.ALICE : TestUsers.BOB;
                assertThat(data.createdBy(alias)).isEqualTo(winner);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- (b) deterministic overlap through a row lock ----

    @Test
    void shouldBlockOnUncommittedInsertOfSameAliasThenReturn409WhenHolderCommits() throws Exception {
        String alias = freshAlias();
        String url = "https://example.com/lock-commit/" + alias;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insertUncommitted(holder, alias);

            CompletableFuture<HttpResponse<String>> request = api.postAsync(TestUsers.ALICE, api.createBody(url, alias));
            awaitRequestBlockedOnLock();
            assertThat(request).as("the request is blocked by the uncommitted row, not finished").isNotDone();

            holder.commit();

            HttpResponse<String> response = request.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(api.json(response).path("errorCode").asText()).isEqualTo("ALIAS_ALREADY_EXISTS");
        }
        assertThat(data.countByCode(alias)).isEqualTo(1);
        assertThat(data.createdBy(alias)).isEqualTo(ShortUrlTestData.SEED_OWNER);
        assertThat(data.countByOriginalUrl(url)).isZero();
    }

    @Test
    void shouldBlockOnUncommittedInsertOfSameAliasThenReturn201WhenHolderRollsBack() throws Exception {
        String alias = freshAlias();
        String url = "https://example.com/lock-rollback/" + alias;

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insertUncommitted(holder, alias);

            CompletableFuture<HttpResponse<String>> request = api.postAsync(TestUsers.ALICE, api.createBody(url, alias));
            awaitRequestBlockedOnLock();
            assertThat(request).as("the request is blocked by the uncommitted row, not finished").isNotDone();

            holder.rollback();

            HttpResponse<String> response = request.get(30, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(api.json(response).path("shortCode").asText()).isEqualTo(alias);
        }
        assertThat(data.countByCode(alias)).isEqualTo(1);
        assertThat(data.createdBy(alias)).isEqualTo(TestUsers.ALICE);
    }

    private static void insertUncommitted(Connection holder, String alias) throws SQLException {
        try (PreparedStatement ps = holder.prepareStatement(
                "INSERT INTO short_url (short_code, original_url, custom_alias, created_by) VALUES (?, ?, true, ?)")) {
            ps.setString(1, alias);
            ps.setString(2, "https://seed.example/" + alias);
            ps.setString(3, ShortUrlTestData.SEED_OWNER);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    /** Polls pg_stat_activity until an INSERT into short_url from another backend waits on a lock. */
    private void awaitRequestBlockedOnLock() {
        Awaitility.await("create request blocked on the alias row lock")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    Integer waiting = jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                                    + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'"
                                    + " AND query ILIKE 'insert into short_url%'", Integer.class);
                    return waiting != null && waiting > 0;
                });
    }
}
