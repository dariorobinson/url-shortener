package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC4: extending the shared Testcontainers base starts a real PostgreSQL container, Flyway runs
 * against it (even with zero application migrations at this point), and the
 * {@code flyway_schema_history} table exists.
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
}
