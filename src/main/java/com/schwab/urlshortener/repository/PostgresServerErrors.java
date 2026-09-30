package com.schwab.urlshortener.repository;

import java.util.Objects;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads PostgreSQL's structured error fields from any exception chain (Spring, then Hibernate, then
 * pgjdbc). The SQLSTATE and the protocol's constraint field do not depend on the server's
 * {@code lc_messages}, unlike the constraint name Hibernate parses from English message text (D65).
 * This is the only class that imports {@code org.postgresql.*}.
 */
public final class PostgresServerErrors {

    /** SQLSTATE for a unique constraint violation. */
    public static final String UNIQUE_VIOLATION = "23505";

    /** Guards against cyclic cause chains. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private PostgresServerErrors() {
    }

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
     * @return the SQLSTATE of the first {@link PSQLException} in the cause chain, or empty if there is none or it
     *         carries no server message
     */
    public static Optional<String> sqlState(Throwable t) {
        return serverError(t).map(ServerErrorMessage::getSQLState);
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
