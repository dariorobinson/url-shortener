package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC4 (US-001): extending the shared Testcontainers base starts a real PostgreSQL container,
 * Flyway runs against it, and the {@code flyway_schema_history} table exists.
 *
 * <p>US-002 adds the black-box half of AC1's proof: the same full application context that a
 * running app would start (random port, {@code test} profile config) applies the V1 migration
 * successfully. {@code ShortUrlSchemaTest} (Surefire, {@code @RepositoryTest} slice) proves the
 * same fact in a narrower context; this test proves it end-to-end.
 */
class FlywaySchemaHistoryIT extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateFlywaySchemaHistoryTable() {
        String regclass = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.flyway_schema_history')::text", String.class);

        assertThat(regclass).isEqualTo("flyway_schema_history");
    }

    @Test
    void shouldRecordV1MigrationAsSuccessful() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT description, success FROM flyway_schema_history WHERE version = '1'");

        assertThat(row.get("description")).isEqualTo("create short url");
        assertThat(row.get("success")).isEqualTo(true);
    }
}
