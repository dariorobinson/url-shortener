package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.support.RepositoryTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC5, D8: V2 created {@code click_event} with exactly the columns, constraints and index from the design.
 * {@code ddl-auto=validate} alone checks neither nullability, defaults nor constraints, so they are asserted here.
 * An exact column list makes "no IP address, user agent or referrer column" true by construction.
 */
@RepositoryTest
class ClickEventSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyV2MigrationSuccessfully() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version, description, success FROM flyway_schema_history WHERE version = '2'");

        assertThat(row.get("version")).isEqualTo("2");
        assertThat(row.get("description")).isEqualTo("create click event");
        assertThat(row.get("success")).isEqualTo(true);
    }

    @Test
    void shouldCreateExactlyTheThreeColumnsAndNoIpUserAgentOrReferrerColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'click_event'
                ORDER BY ordinal_position
                """);

        assertThat(columns).hasSize(3);
        assertColumn(columns.get(0), "id", "bigint", "NO", "nextval('click_event_id_seq'::regclass)");
        assertColumn(columns.get(1), "short_url_id", "bigint", "NO", null);
        assertColumn(columns.get(2), "clicked_at", "timestamp with time zone", "NO", "now()");
    }

    private static void assertColumn(Map<String, Object> column, String name, String dataType, String isNullable,
            String columnDefault) {
        assertThat(column.get("column_name")).isEqualTo(name);
        assertThat(column.get("data_type")).isEqualTo(dataType);
        assertThat(column.get("character_maximum_length")).isNull();
        assertThat(column.get("is_nullable")).isEqualTo(isNullable);
        assertThat(column.get("column_default")).isEqualTo(columnDefault);
    }

    @Test
    void shouldDeclareOnlyThePrimaryKeyAndTheNamedForeignKey() {
        List<Map<String, Object>> constraints = jdbcTemplate.queryForList("""
                SELECT conname, contype
                FROM pg_constraint
                WHERE conrelid = 'public.click_event'::regclass AND contype IN ('p','u','c','f')
                """);

        Map<String, String> byName = constraints.stream().collect(Collectors.toMap(
                row -> (String) row.get("conname"), row -> (String) row.get("contype")));

        assertThat(byName).containsOnly(
                Map.entry("click_event_pkey", "p"),
                Map.entry("fk_click_event_short_url", "f"));
    }

    @Test
    void shouldDefineTheForeignKeyToShortUrlWithTheDefaultNoActionRule() {
        String definition = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'public.click_event'::regclass AND conname = 'fk_click_event_short_url'
                """, String.class);

        assertThat(definition).isEqualTo("FOREIGN KEY (short_url_id) REFERENCES short_url(id)");
    }

    @Test
    void shouldCreateTheCompositeIndexOnShortUrlIdAndClickedAt() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'click_event'"
                        + " ORDER BY indexname");

        assertThat(indexes).extracting(row -> row.get("indexname"))
                .containsExactly("click_event_pkey", "ix_click_event_short_url_id_clicked_at");
        assertThat(indexes).filteredOn(row -> "ix_click_event_short_url_id_clicked_at".equals(row.get("indexname")))
                .singleElement().satisfies(row -> assertThat(row.get("indexdef")).isEqualTo(
                        "CREATE INDEX ix_click_event_short_url_id_clicked_at ON public.click_event"
                                + " USING btree (short_url_id, clicked_at)"));
    }
}
