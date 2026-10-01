package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.support.RepositoryTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC1: proves the V1 migration created {@code short_url} with exactly the columns, defaults, and
 * constraints from {@code docs/architecture.md}. {@code ddl-auto=validate} only checks that
 * tables/columns exist and have compatible types — it does not check nullability, length,
 * defaults, or constraints, which is why the column/constraint assertions below exist separately.
 */
@RepositoryTest
class ShortUrlSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void shouldApplyV1MigrationSuccessfully() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version, description, success FROM flyway_schema_history WHERE version = '1'");

        assertThat(row.get("version")).isEqualTo("1");
        assertThat(row.get("description")).isEqualTo("create short url");
        assertThat(row.get("success")).isEqualTo(true);
    }

    @Test
    void shouldCreateShortUrlColumnsExactlyAsSpecified() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'short_url'
                ORDER BY ordinal_position
                """);

        assertThat(columns).hasSize(14);

        assertColumn(columns.get(0), "id", "bigint", null, "NO", "nextval('short_url_id_seq'::regclass)");
        assertColumn(columns.get(1), "short_code", "text", null, "NO", null);
        assertColumn(columns.get(2), "original_url", "text", null, "NO", null);
        assertColumn(columns.get(3), "custom_alias", "boolean", null, "NO", "false");
        assertColumn(columns.get(4), "status", "character varying", 16, "NO", "'ACTIVE'::character varying");
        assertColumn(columns.get(5), "click_count", "bigint", null, "NO", "0");
        assertColumn(columns.get(6), "last_accessed_at", "timestamp with time zone", null, "YES", null);
        assertColumn(columns.get(7), "created_by", "character varying", 100, "NO", null);
        assertColumn(columns.get(8), "created_at", "timestamp with time zone", null, "NO", "now()");
        assertColumn(columns.get(9), "updated_at", "timestamp with time zone", null, "NO", "now()");
        assertColumn(columns.get(10), "deleted_at", "timestamp with time zone", null, "YES", null);
        assertColumn(columns.get(11), "deleted_by", "character varying", 100, "YES", null);
        assertColumn(columns.get(12), "version", "bigint", null, "NO", "0");
        // V3 (D106): appended by ALTER TABLE, so it is the last column; nullable, no default = never expires.
        assertColumn(columns.get(13), "expires_at", "timestamp with time zone", null, "YES", null);
    }

    private static void assertColumn(Map<String, Object> column, String name, String dataType,
                                      Integer charMaxLength, String isNullable, String columnDefault) {
        assertThat(column.get("column_name")).isEqualTo(name);
        assertThat(column.get("data_type")).isEqualTo(dataType);
        assertThat(column.get("character_maximum_length")).isEqualTo(charMaxLength);
        assertThat(column.get("is_nullable")).isEqualTo(isNullable);
        assertThat(column.get("column_default")).isEqualTo(columnDefault);
    }

    @Test
    void shouldDeclareAllNamedConstraints() {
        List<Map<String, Object>> constraints = jdbcTemplate.queryForList("""
                SELECT conname, contype
                FROM pg_constraint
                WHERE conrelid = 'public.short_url'::regclass AND contype IN ('p','u','c','f')
                """);

        Map<String, String> byName = constraints.stream().collect(Collectors.toMap(
                row -> (String) row.get("conname"), row -> (String) row.get("contype")));

        assertThat(byName).containsOnly(
                Map.entry("short_url_pkey", "p"),
                Map.entry("uk_short_url_short_code", "u"),
                Map.entry("ck_short_url_status", "c"),
                Map.entry("ck_short_url_click_count", "c"),
                Map.entry("ck_short_url_code_format", "c"),
                Map.entry("ck_short_url_original_url_length", "c"),
                Map.entry("ck_short_url_deleted_consistency", "c"),
                Map.entry("ck_short_url_expires_after_created", "c"));
    }

    @Test
    void shouldValidateEntityMappingAgainstFlywaySchema() {
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
    }
}
