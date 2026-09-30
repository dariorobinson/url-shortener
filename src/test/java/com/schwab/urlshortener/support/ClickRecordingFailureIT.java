package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ShortUrlTestData.LifecycleState;
import com.schwab.urlshortener.validation.LocationEncoder;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-010 AC3, D12, D64, D93: fail open, with a real failure inside PostgreSQL. A PL/pgSQL trigger created and
 * dropped by the test itself fails either the click_event INSERT or the counter UPDATE; the trigger message
 * deliberately carries the stored URL, so any logging of the exception message would put the URL in the log.
 * The trigger is dropped in setup and teardown, so it can never leak into another test.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClickRecordingFailureIT extends IntegrationTestBase {

    private static final String CODE = "Fail0001";
    private static final String TOKEN = "u010-secret";
    // Non-ASCII, so the raw and the D75-encoded forms differ and both are checked.
    private static final String STORED = "https://example.com/café/中?token=" + TOKEN;
    private static final String ENCODED = LocationEncoder.encode(STORED);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private ShortUrlTestData data;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        data = new ShortUrlTestData(jdbc);
        data.dropClickFailures();
        data.truncate();
    }

    @AfterEach
    void tearDown() {
        data.dropClickFailures();
    }

    private HttpResponse<String> call(String method) throws Exception {
        return api.send(method, "/" + CODE, null, null, null);
    }

    private void assertUnchangedRedirect(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().allValues("Location")).containsExactly(ENCODED);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().firstValue("Pragma")).isEmpty();
        assertThat(response.headers().firstValue("Expires")).isEmpty();
        assertThat(response.body()).isEmpty();
    }

    private void assertLoggedOnceWithoutTheUrl(String logs) {
        String prefix = "Click not recorded: code=" + CODE;
        // Positive capture: the WARN line is there, exactly once, with code, id, class and SQLSTATE.
        assertThat(logs).contains(prefix);
        assertThat(logs.split(Pattern.quote(prefix), -1)).as("exactly one WARN line").hasSize(2);
        String line = logs.lines().filter(l -> l.contains(prefix)).findFirst().orElseThrow();
        assertThat(line).contains(" WARN ").contains("id=" + data.shortUrlId(CODE)).contains("sqlState=P0001")
                .containsPattern("exception=\\w+");
        // Never: the raw URL, its D75-encoded form, the token, the trigger text, a stack trace, an ERROR line.
        assertThat(logs).doesNotContain(STORED).doesNotContain(ENCODED).doesNotContain(TOKEN)
                .doesNotContain("example.com/caf").doesNotContain("injected click failure")
                .doesNotContain("\tat ").doesNotContain(" ERROR ");
    }

    // ---- Positive controls: the URL really is in the database's message ----

    @Test
    void shouldCarryTheStoredUrlInTheInsertTriggerMessageSoItsAbsenceFromTheLogMeansSomething() {
        data.seed(CODE, "ACTIVE", STORED);
        data.failClickInserts();
        long id = data.shortUrlId(CODE);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO click_event (short_url_id) VALUES (?)", id))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    assertThat(PostgresErrors.sqlState(e)).isEqualTo("P0001");
                    assertThat(PostgresErrors.serverError(e).orElseThrow().getMessage()).contains(TOKEN)
                            .contains("injected click failure");
                });
        assertThat(data.clickEventCount()).isZero();
    }

    @Test
    void shouldCarryTheStoredUrlInTheUpdateTriggerMessageSoItsAbsenceFromTheLogMeansSomething() {
        data.seed(CODE, "ACTIVE", STORED);
        data.failClickUpdates();

        assertThatThrownBy(() -> jdbc.update("UPDATE short_url SET click_count = click_count + 1 WHERE short_code = ?",
                CODE))
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    assertThat(PostgresErrors.sqlState(e)).isEqualTo("P0001");
                    assertThat(PostgresErrors.serverError(e).orElseThrow().getMessage()).contains(TOKEN);
                });
        assertThat(data.lifecycleState(CODE).clickCount()).isZero();
    }

    // ---- AC3: the INSERT fails ----

    @Test
    void shouldStillRedirectAndLeaveNoPartialClickWhenTheEventInsertFails(CapturedOutput output) throws Exception {
        data.seed(CODE, "ACTIVE", STORED);
        LifecycleState before = data.lifecycleState(CODE);
        HttpResponse<String> head = call("HEAD");
        data.failClickInserts();

        HttpResponse<String> get = call("GET");

        assertUnchangedRedirect(get);
        assertThat(ApiClient.stableHeaders(get)).isEqualTo(ApiClient.stableHeaders(head));
        // No partial click: the UPDATE ran first, and was rolled back together with the failed INSERT.
        assertThat(data.lifecycleState(CODE)).isEqualTo(before);
        assertThat(data.clickEventCount()).isZero();
        assertLoggedOnceWithoutTheUrl(output.getAll());
    }

    // ---- AC3: the UPDATE fails ----

    @Test
    void shouldStillRedirectWhenTheCounterUpdateFails(CapturedOutput output) throws Exception {
        data.seed(CODE, "ACTIVE", STORED);
        LifecycleState before = data.lifecycleState(CODE);
        HttpResponse<String> head = call("HEAD");
        data.failClickUpdates();

        HttpResponse<String> get = call("GET");

        assertUnchangedRedirect(get);
        assertThat(ApiClient.stableHeaders(get)).isEqualTo(ApiClient.stableHeaders(head));
        assertThat(data.lifecycleState(CODE)).isEqualTo(before);
        assertThat(data.clickEventCount()).isZero();
        assertLoggedOnceWithoutTheUrl(output.getAll());
    }

    // ---- Recovery, and HEAD never reaches the failing path ----

    @Test
    void shouldCountAgainOnceTheFailureIsRemovedAndNeverLogForHead(CapturedOutput output) throws Exception {
        data.seed(CODE, "ACTIVE", STORED);
        data.failClickInserts();
        assertThat(call("HEAD").statusCode()).isEqualTo(302);
        assertThat(output.getAll()).doesNotContain("Click not recorded");
        assertThat(call("GET").statusCode()).isEqualTo(302);
        assertThat(output.getAll()).contains("Click not recorded: code=" + CODE);

        data.dropClickFailures();
        assertUnchangedRedirect(call("GET"));

        assertThat(data.lifecycleState(CODE).clickCount()).isEqualTo(1);
        assertThat(data.clickEventCount(CODE)).isEqualTo(1);
    }
}
