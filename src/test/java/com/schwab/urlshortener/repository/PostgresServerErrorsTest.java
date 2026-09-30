package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/** D65: recognising exactly one unique constraint from pgjdbc's structured fields (unit, no database). */
class PostgresServerErrorsTest {

    private static final String UNIQUE = ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT;

    /** Field codes: S severity, C SQLSTATE, n constraint name, M message. */
    private static PSQLException psql(String sqlState, String constraint) {
        return new PSQLException(new ServerErrorMessage(
                "SERROR\0C" + sqlState + "\0n" + constraint + "\0Mx\0"), true);
    }

    @Test
    void shouldReturnEmptyForNull() {
        assertThat(PostgresServerErrors.serverError(null)).isEmpty();
        assertThat(PostgresServerErrors.isUniqueViolation(null, UNIQUE)).isFalse();
    }

    @Test
    void shouldReadServerErrorFromADirectPsqlException() {
        PSQLException error = psql("23505", UNIQUE);

        assertThat(PostgresServerErrors.serverError(error)).hasValueSatisfying(m -> {
            assertThat(m.getSQLState()).isEqualTo("23505");
            assertThat(m.getConstraint()).isEqualTo(UNIQUE);
        });
        assertThat(PostgresServerErrors.isUniqueViolation(error, UNIQUE)).isTrue();
    }

    @Test
    void shouldFindThePsqlExceptionThroughSpringAndHibernateWrappers() {
        // Spring DataIntegrityViolationException -> Hibernate ConstraintViolationException -> PSQLException
        Throwable hibernate = new ConstraintViolationException(
                "wrapped", new SQLException("wrapped", psql("23505", UNIQUE)), UNIQUE);
        DataIntegrityViolationException spring = new DataIntegrityViolationException("wrapped", hibernate);

        assertThat(PostgresServerErrors.isUniqueViolation(spring, UNIQUE)).isTrue();
    }

    @Test
    void shouldTerminateOnACyclicCauseChain() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(PostgresServerErrors.serverError(first)).isEmpty();
        assertThat(PostgresServerErrors.isUniqueViolation(first, UNIQUE)).isFalse();
    }

    @Test
    void shouldReturnEmptyWhenThePsqlExceptionHasNoServerMessage() {
        PSQLException clientSide = new PSQLException("client side failure", PSQLState.UNEXPECTED_ERROR);

        assertThat(PostgresServerErrors.serverError(clientSide)).isEmpty();
        assertThat(PostgresServerErrors.isUniqueViolation(clientSide, UNIQUE)).isFalse();
    }

    @Test
    void shouldNotTreatACheckViolationOnTheSameNameAsUnique() {
        assertThat(PostgresServerErrors.isUniqueViolation(psql("23514", UNIQUE), UNIQUE)).isFalse();
    }

    @Test
    void shouldNotTreatAUniqueViolationOnAnotherConstraintAsTheShortCodeConflict() {
        assertThat(PostgresServerErrors.isUniqueViolation(psql("23505", "uk_other"), UNIQUE)).isFalse();
    }

    @Test
    void shouldNotFindAPsqlExceptionBeyondTheDepthGuard() {
        Throwable chain = psql("23505", UNIQUE);
        for (int i = 0; i < 40; i++) {
            chain = new RuntimeException("wrapper " + i, chain);
        }

        assertThat(PostgresServerErrors.serverError(chain)).isEmpty();
    }

    @Test
    void shouldRejectANullConstraintName() {
        assertThatThrownBy(() -> PostgresServerErrors.isUniqueViolation(psql("23505", UNIQUE), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReadTheSqlStateFromADirectPsqlException() {
        assertThat(PostgresServerErrors.sqlState(psql("P0001", "c"))).contains("P0001");
    }

    @Test
    void shouldReadTheSqlStateThroughSpringAndHibernateWrappers() {
        Throwable hibernate = new ConstraintViolationException(
                "wrapped", new SQLException("wrapped", psql("23503", "fk_x")), "fk_x");

        assertThat(PostgresServerErrors.sqlState(new DataIntegrityViolationException("wrapped", hibernate)))
                .contains("23503");
    }

    @Test
    void shouldReturnAnEmptySqlStateForNullOrAChainWithoutSqlException() {
        assertThat(PostgresServerErrors.sqlState(null)).isEmpty();
        assertThat(PostgresServerErrors.sqlState(new RuntimeException("plain", new IllegalStateException()))).isEmpty();
        // Positive control: the same helper does find a state when one exists.
        assertThat(PostgresServerErrors.sqlState(psql("23505", "c"))).isPresent();
    }

    @Test
    void shouldFallBackToTheClientSqlStateWhenThereIsNoServerMessage() {
        assertThat(PostgresServerErrors.sqlState(new PSQLException("client side", PSQLState.UNEXPECTED_ERROR)))
                .contains("99999");
        assertThat(PostgresServerErrors.sqlState(
                new DataAccessResourceFailureException("x", new SQLException("x", "08006")))).contains("08006");
        // A SQLException without a state is skipped in favour of the next one in the chain.
        assertThat(PostgresServerErrors.sqlState(new SQLException("no state",
                new PSQLException("x", PSQLState.CONNECTION_FAILURE)))).contains("08006");
        // A blank state is skipped too.
        assertThat(PostgresServerErrors.sqlState(new SQLException("blank", " ",
                new SQLException("x", "08006")))).contains("08006");
        // The server state still wins over a client-side one further out in the chain.
        assertThat(PostgresServerErrors.sqlState(new SQLException("outer", "08006", psql("23503", "c"))))
                .contains("23503");
    }

    @Test
    void shouldNotTreatAClientSideUniqueStateAsAUniqueViolation() {
        PSQLException clientSide = new PSQLException("x", PSQLState.UNIQUE_VIOLATION);

        assertThat(PostgresServerErrors.isUniqueViolation(clientSide, UNIQUE)).isFalse();
        // Positive control: the client state is visible to sqlState, so the false above is meaningful.
        assertThat(PostgresServerErrors.sqlState(clientSide)).contains("23505");
    }

    @Test
    void shouldTerminateTheSqlStateFallbackOnACyclicCauseChain() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(PostgresServerErrors.sqlState(first)).isEmpty();
    }
}
