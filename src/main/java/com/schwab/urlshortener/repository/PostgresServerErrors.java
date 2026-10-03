package com.schwab.urlshortener.repository;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads PostgreSQL's structured error fields from any exception chain (Spring, then Hibernate, then
 * pgjdbc). The SQLSTATE and the protocol's constraint field do not depend on the server's
 * {@code lc_messages}, unlike the constraint name Hibernate parses from English message text (D65).
 * This is the only class that imports {@code org.postgresql.*}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PostgresServerErrors {

    /** SQLSTATE for a unique constraint violation. */
    public static final String UNIQUE_VIOLATION = "23505";

    /** Guards against cyclic cause chains. */
    private static final int MAX_CAUSE_DEPTH = 32;

    /**
     * @return the server error of the first {@link PSQLException} in the cause chain, or empty if there is
     *         none or it carries no server message
     */
    public static Optional<ServerErrorMessage> serverError(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof PSQLException psql) {
                return Optional.ofNullable(psql.getServerErrorMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * @return the SQLSTATE of the server error (first {@link PSQLException} with a server message), else the first
     *         non-blank {@link SQLException#getSQLState()} in the cause chain (client-side states such as
     *         {@code 08006}), else empty. {@link #serverError} and {@link #isUniqueViolation} stay server-message-only,
     *         so a client-side {@code 23505} is never a "code taken" collision.
     */
    public static Optional<String> sqlState(Throwable t) {
        Optional<String> server = serverError(t).map(ServerErrorMessage::getSQLState);
        if (server.isPresent()) {
            return server;
        }
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getSQLState() != null && !sql.getSQLState().isBlank()) {
                return Optional.of(sql.getSQLState());
            }
        }
        return Optional.empty();
    }

    /**
     * @return true only for SQLSTATE {@value #UNIQUE_VIOLATION} on exactly the named constraint
     * @throws NullPointerException if {@code constraintName} is null
     */
    public static boolean isUniqueViolation(Throwable t, String constraintName) {
        Objects.requireNonNull(constraintName, "constraintName");
        return serverError(t)
                .filter(m -> UNIQUE_VIOLATION.equals(m.getSQLState()) && constraintName.equals(m.getConstraint()))
                .isPresent();
    }
}
