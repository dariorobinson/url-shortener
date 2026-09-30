package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.support.PostgresErrors;
import com.schwab.urlshortener.support.RepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Raw-SQL integrity test for {@code click_event}: the insert goes through {@link JdbcTemplate}, bypassing the
 * entity, so it hits the database foreign key rather than an application check. One failing statement per test,
 * and it is the last database action, because PostgreSQL aborts the transaction after an error.
 */
@RepositoryTest
class ClickEventConstraintsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRejectAClickForAnUnknownShortUrlWithTheForeignKeyViolation() {
        // Positive control: a click for an existing link is accepted by the same statement.
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO short_url (short_code, original_url, created_by)
                VALUES ('fk12345', 'https://example.com/', 'alice')
                RETURNING id
                """, Long.class);
        assertThat(jdbcTemplate.update("INSERT INTO click_event (short_url_id) VALUES (?)", id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM click_event", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO click_event (short_url_id) VALUES (?)", id + 1000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> {
                    assertThat(PostgresErrors.sqlState(thrown)).isEqualTo("23503");
                    assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("fk_click_event_short_url");
                });
    }
}
