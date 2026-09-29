package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.fail;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Test helper: unwraps a {@link Throwable} chain to the underlying pgjdbc
 * {@link PSQLException}, so constraint tests can assert the SQLSTATE and constraint name
 * reported by PostgreSQL instead of matching on exception message text. Works for both the
 * {@code JdbcTemplate} path ({@code DataIntegrityViolationException} -&gt; {@code PSQLException})
 * and the JPA path ({@code DataIntegrityViolationException} -&gt; Hibernate
 * {@code ConstraintViolationException} -&gt; {@code PSQLException}).
 */
public final class PostgresErrors {

    private PostgresErrors() {
    }

    public static Optional<ServerErrorMessage> serverError(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof PSQLException psqlException) {
                return Optional.ofNullable(psqlException.getServerErrorMessage());
            }
            current = current.getCause();
        }
        return Optional.empty();
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
