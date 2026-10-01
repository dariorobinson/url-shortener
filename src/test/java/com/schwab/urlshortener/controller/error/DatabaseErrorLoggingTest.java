package com.schwab.urlshortener.controller.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.schwab.urlshortener.repository.PostgresServerErrors;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.support.PostgresErrors;
import com.schwab.urlshortener.support.RepositoryTest;
import com.schwab.urlshortener.util.domain.ShortUrl;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * D64: a CHECK violation forced through the real stack (JPA, Hibernate, pgjdbc, PostgreSQL) never puts the
 * server's "Failing row contains (...)" detail, the URL or the username into the logs. The failure is then
 * handed to the real advice's catch-all, which is the one place that logs the exception with its cause chain.
 */
@RepositoryTest
@ExtendWith(OutputCaptureExtension.class)
class DatabaseErrorLoggingTest {

    private static final String URL_MARKER = "secret-url-marker";
    private static final String USER_MARKER = "secret-user-marker";

    @Autowired
    private ShortUrlRepository repository;

    @Test
    void shouldNeverLogRowDataWhenACheckViolationIsLoggedByTheAdvice(CapturedOutput output) throws Exception {
        String tooLongUrl = "https://example.com/" + URL_MARKER + "a".repeat(2049);
        ShortUrl row = ShortUrl.create("Abc1234", tooLongUrl, false, USER_MARKER, Instant.parse("2026-09-29T00:00:00Z"));

        Throwable thrown = catchThrowable(() -> repository.saveAndFlush(row));

        // The violation is real and is the CHECK, not a unique violation (so the absence checks are about it).
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo("23514");
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("ck_short_url_original_url_length");
        assertThat(PostgresServerErrors.isUniqueViolation(thrown, ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT))
                .isFalse();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/urls");
        var response = new GlobalExceptionHandler().handleUnexpected((Exception) thrown, request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);

        String logged = output.getAll();
        // Positive capture: the advice logged this very failure, with the driver's message in the cause chain.
        assertThat(logged).contains("Unhandled exception: method=POST path=/api/v1/urls")
                .contains("ck_short_url_original_url_length");
        // Hibernate's own SQL-error logging is off: expected violations are handled and logged by the service.
        assertThat(LoggerFactory.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper").isErrorEnabled())
                .isFalse();
        // The console pattern abbreviates the logger name, so this matches log lines, not stack frames.
        assertThat(logged).doesNotContain("o.h.engine.jdbc.spi.SqlExceptionHelper");
        assertThat(logged).doesNotContain("Failing row contains")
                .doesNotContain(URL_MARKER)
                .doesNotContain(USER_MARKER);
    }
}
