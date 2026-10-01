package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.support.PostgresErrors;
import com.schwab.urlshortener.support.RepositoryTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * US-016 AC9 and AC8 against real PostgreSQL: the V3 column and CHECK (D106, D107), the entity round trip, and the
 * click UPDATE's expiry guard (D117). A failing statement is always the last database action of its test, because
 * PostgreSQL aborts the transaction after an error.
 */
@RepositoryTest
class ShortUrlExpiryRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00.000001Z");
    private static final Instant EXPIRY = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-15T00:00:00Z");

    @Autowired
    private ShortUrlRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private int insert(String code, Instant createdAt, Instant expiresAt) {
        return jdbc.update("INSERT INTO short_url (short_code, original_url, created_by, created_at, updated_at,"
                + " expires_at) VALUES (?, 'https://example.com/', 'alice', ?, ?, ?)", code, utc(createdAt),
                utc(createdAt), expiresAt == null ? null : utc(expiresAt));
    }

    private Instant expiresAtOf(long id) {
        Timestamp value = jdbc.queryForObject("SELECT expires_at FROM short_url WHERE id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    @Test
    void shouldAcceptANullOrLaterExpiry() {
        assertThat(insert("noExp01", CREATED, null)).isEqualTo(1);
        assertThat(insert("okExp01", CREATED, CREATED.plusNanos(1000))).isEqualTo(1);
    }

    @Test
    void shouldRejectAnExpiryEqualToCreatedAtWithTheNamedCheck() {
        Throwable thrown = catchThrowable(() -> insert("badExp1", CREATED, CREATED));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo("23514");
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("ck_short_url_expires_after_created");
    }

    @Test
    void shouldRejectAnExpiryBeforeCreatedAtWithTheNamedCheck() {
        Throwable thrown = catchThrowable(() -> insert("badExp2", CREATED, CREATED.minusSeconds(1)));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo("23514");
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("ck_short_url_expires_after_created");
    }

    @Test
    void shouldPersistAndReloadTheExpiryThroughTheEntity() {
        Long id = repository.saveAndFlush(
                ShortUrl.create("round01", "https://example.com/", false, "alice", CREATED, EXPIRY)).getId();
        entityManager.clear();

        assertThat(repository.findById(id)).get().extracting(ShortUrl::getExpiresAt).isEqualTo(EXPIRY);
        assertThat(expiresAtOf(id)).isEqualTo(EXPIRY);
    }

    @Test
    void shouldWriteAChangedExpiryWithOneVersionBumpAndNothingForTheSameValue() {
        ShortUrl url = repository.saveAndFlush(
                ShortUrl.create("change1", "https://example.com/", false, "alice", CREATED, EXPIRY));
        Long id = url.getId();

        url.changeExpiry(EXPIRY, LATER);                      // the same value: not a change (D125)
        repository.flush();
        assertThat(jdbc.queryForObject("SELECT version FROM short_url WHERE id = ?", Long.class, id)).isZero();

        url.changeExpiry(null, LATER);                        // clear: a real change
        repository.flush();
        assertThat(jdbc.queryForObject("SELECT version FROM short_url WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(expiresAtOf(id)).isNull();
    }

    @Test
    void shouldNotCountAClickAtOrAfterTheExpiry() {
        Long id = repository.saveAndFlush(
                ShortUrl.create("click01", "https://example.com/", false, "alice", CREATED, EXPIRY)).getId();

        assertThat(repository.recordClick(id, EXPIRY)).isZero();
        assertThat(repository.recordClick(id, EXPIRY.plusNanos(1000))).isZero();
        assertThat(jdbc.queryForObject("SELECT click_count FROM short_url WHERE id = ?", Long.class, id)).isZero();
        // Positive control: one microsecond before the expiry the same row is counted.
        assertThat(repository.recordClick(id, EXPIRY.minusNanos(1000))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT click_count FROM short_url WHERE id = ?", Long.class, id))
                .isEqualTo(1L);
    }

    @Test
    void shouldAlwaysCountAClickOnALinkWithoutAnExpiry() {
        Long id = repository.saveAndFlush(
                ShortUrl.create("click02", "https://example.com/", false, "alice", CREATED)).getId();

        assertThat(repository.recordClick(id, Instant.parse("2099-01-01T00:00:00Z"))).isEqualTo(1);
    }
}
