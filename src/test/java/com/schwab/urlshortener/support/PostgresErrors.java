package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.fail;

import com.schwab.urlshortener.repository.PostgresServerErrors;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.postgresql.util.ServerErrorMessage;

/**
 * Test helper: unwraps a {@link Throwable} chain, through the production {@link PostgresServerErrors}, to
 * the underlying pgjdbc {@code PSQLException}, so constraint tests can assert the SQLSTATE and constraint name
 * reported by PostgreSQL instead of matching on exception message text. Works for both the
 * {@code JdbcTemplate} path ({@code DataIntegrityViolationException} -&gt; {@code PSQLException})
 * and the JPA path ({@code DataIntegrityViolationException} -&gt; Hibernate
 * {@code ConstraintViolationException} -&gt; {@code PSQLException}).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PostgresErrors {

    public static Optional<ServerErrorMessage> serverError(Throwable t) {
        return PostgresServerErrors.serverError(t);
    }

    public static String sqlState(Throwable t) {
        return serverError(t)
                .map(ServerErrorMessage::getSQLState)
                .orElseGet(() -> fail("No PSQLException found in the cause chain of " + t.getClass()));
    }

    public static String constraintName(Throwable t) {
        return serverError(t)
                .map(ServerErrorMessage::getConstraint)
                .orElseGet(() -> fail("No PSQLException found in the cause chain of " + t.getClass()));
    }
}
