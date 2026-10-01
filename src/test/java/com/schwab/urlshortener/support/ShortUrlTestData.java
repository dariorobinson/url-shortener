package com.schwab.urlshortener.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Arranges and inspects {@code short_url} and {@code click_event} rows for integration tests when the API cannot
 * express it (seeding colliding rows, counting rows by marker). Rows seeded here are owned by
 * {@value #SEED_OWNER}. Not a Spring bean: construct it with the context's {@link JdbcTemplate}.
 */
public final class ShortUrlTestData {

    public static final String SEED_OWNER = "qa-seed";

    private final JdbcTemplate jdbc;

    public ShortUrlTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Test isolation: HTTP requests run on server threads, so rollback-based isolation is not possible.
     * Truncation assumes serial test execution, as the scripted short-code generator seam does. Enabling
     * parallel execution means revisiting truncation and the seam together.
     */
    public void truncate() {
        jdbc.execute("TRUNCATE TABLE click_event, short_url");
    }

    /**
     * Makes every click_event INSERT fail with a PL/pgSQL trigger whose message carries the stored URL, so a
     * log that leaked exception messages would show it. Drop with {@link #dropClickFailures()}.
     */
    public void failClickInserts() {
        jdbc.execute("CREATE FUNCTION test_fail_click_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'injected click failure for %',"
                + " (SELECT original_url FROM short_url WHERE id = NEW.short_url_id); END $$");
        jdbc.execute("CREATE TRIGGER test_fail_click BEFORE INSERT ON click_event FOR EACH ROW"
                + " EXECUTE FUNCTION test_fail_click_insert()");
    }

    /** Makes every short_url click_count UPDATE fail, with the stored URL in the message. */
    public void failClickUpdates() {
        jdbc.execute("CREATE FUNCTION test_fail_click_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'injected click failure for %', NEW.original_url; END $$");
        jdbc.execute("CREATE TRIGGER test_fail_click BEFORE UPDATE OF click_count ON short_url FOR EACH ROW"
                + " EXECUTE FUNCTION test_fail_click_update()");
    }

    /** Removes either injected failure; safe to call when none exists. */
    /**
     * US-014: makes every short_url INSERT fail with a raw PostgreSQL error, so create answers a 500. Dropped by
     * {@link #dropClickFailures()} with the other injected failures.
     */
    public void failShortUrlInserts() {
        jdbc.execute("CREATE FUNCTION test_fail_short_url_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'injected short_url failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_fail_short_url BEFORE INSERT ON short_url FOR EACH ROW"
                + " EXECUTE FUNCTION test_fail_short_url_insert()");
    }

    public void dropClickFailures() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_click ON click_event");
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_click ON short_url");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_click_insert()");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_click_update()");
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_short_url ON short_url");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_short_url_insert()");
    }

    public void seed(String code, String status) {
        seed(code, status, "https://seed.example/" + code);
    }

    public void seed(String code, String status, String originalUrl) {
        seed(code, status, originalUrl, SEED_OWNER);
    }

    /**
     * Raw-SQL row owned by {@code owner} in any status (no lifecycle API exists to reach DEACTIVATED or
     * DELETED). A DELETED row carries {@code deleted_at} and {@code deleted_by} as D44 requires.
     */
    public void seed(String code, String status, String originalUrl, String owner) {
        if ("DELETED".equals(status)) {
            jdbc.update("INSERT INTO short_url (short_code, original_url, status, created_by, deleted_at, deleted_by)"
                    + " VALUES (?, ?, 'DELETED', ?, now(), ?)", code, originalUrl, owner, TestUsers.ADMIN);
        } else {
            jdbc.update("INSERT INTO short_url (short_code, original_url, status, created_by) VALUES (?, ?, ?, ?)",
                    code, originalUrl, status, owner);
        }
    }

    /** Sets click data directly: the only way to get non-zero clicks before the redirect exists. */
    public void seedClicks(String code, long clickCount, Instant lastAccessedAt) {
        int updated = jdbc.update("UPDATE short_url SET click_count = ?, last_accessed_at = ? WHERE short_code = ?",
                clickCount, lastAccessedAt == null ? null : Timestamp.from(lastAccessedAt), code);
        if (updated != 1) {
            throw new IllegalStateException("expected exactly one row to seed clicks on");
        }
    }

    /**
     * Inserts one click_event row per instant with an explicit clicked_at, then applies the recorder's effect to the
     * short_url row: click_count += n and last_accessed_at = GREATEST(last_accessed_at, latest) (D94), so the
     * fixtures look like application-written data. Instants are bound as UTC {@link OffsetDateTime}, never
     * {@link Timestamp}, so the fall-back hour cannot depend on the JVM default zone.
     */
    public void seedClickEvents(String code, Instant... clickedAt) {
        if (clickedAt.length == 0) {
            throw new IllegalArgumentException("at least one click");
        }
        long id = shortUrlId(code);
        Instant latest = clickedAt[0];
        for (Instant at : clickedAt) {
            jdbc.update("INSERT INTO click_event (short_url_id, clicked_at) VALUES (?, ?)", id,
                    OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            latest = at.isAfter(latest) ? at : latest;
        }
        jdbc.update("UPDATE short_url SET click_count = click_count + ?,"
                + " last_accessed_at = GREATEST(last_accessed_at, ?) WHERE id = ?", clickedAt.length,
                OffsetDateTime.ofInstant(latest, ZoneOffset.UTC), id);
    }

    /**
     * Moves an existing row to a non-DELETED status (for example DEACTIVATED, which no lifecycle API reaches
     * yet). Use {@link #markDeleted} for DELETED, which must also set the D44 columns.
     */
    public void setStatus(String code, String status) {
        if ("DELETED".equals(status)) {
            throw new IllegalArgumentException("use markDeleted for DELETED (D44 columns)");
        }
        int updated = jdbc.update("UPDATE short_url SET status = ? WHERE short_code = ?", status, code);
        if (updated != 1) {
            throw new IllegalStateException("expected exactly one row to change status");
        }
    }

    /** Moves an existing row to DELETED, satisfying D44. */
    public void markDeleted(String code) {
        int updated = jdbc.update("UPDATE short_url SET status = 'DELETED', deleted_at = now(), deleted_by = ?"
                + " WHERE short_code = ?", TestUsers.ADMIN, code);
        if (updated != 1) {
            throw new IllegalStateException("expected exactly one row to delete");
        }
    }

    /** The columns a read must never change, plus created_at, as read from the database. */
    public Map<String, Object> rowState(String code) {
        return jdbc.queryForMap("SELECT version, updated_at, click_count, last_accessed_at, created_at, status"
                + " FROM short_url WHERE short_code = ?", code);
    }

    /**
     * The lifecycle-relevant columns of one row, read with explicit types. Unlike {@link #rowState}, which
     * {@code GetShortUrlIT} compares as a raw map, this is a typed value so tests can compare and inspect fields.
     */
    public record LifecycleState(String status, long version, Instant updatedAt, Instant deletedAt,
            String deletedBy, long clickCount, Instant lastAccessedAt) {
    }

    /** Reads {@link LifecycleState} for exactly one existing row; throws if there is not exactly one. */
    public LifecycleState lifecycleState(String code) {
        return jdbc.queryForObject("SELECT status, version, updated_at, deleted_at, deleted_by, click_count,"
                + " last_accessed_at FROM short_url WHERE short_code = ?", (rs, row) -> new LifecycleState(
                        rs.getString("status"), rs.getLong("version"), instant(rs.getTimestamp("updated_at")),
                        instant(rs.getTimestamp("deleted_at")), rs.getString("deleted_by"),
                        rs.getLong("click_count"), instant(rs.getTimestamp("last_accessed_at"))), code);
    }

    public long shortUrlId(String code) {
        return jdbc.queryForObject("SELECT id FROM short_url WHERE short_code = ?", Long.class, code);
    }

    /** Number of click_event rows of the link with this code. */
    public int clickEventCount(String code) {
        return count("SELECT count(*) FROM click_event e JOIN short_url s ON s.id = e.short_url_id"
                + " WHERE s.short_code = ?", code);
    }

    /** Number of click_event rows in the whole table. */
    public int clickEventCount() {
        return count("SELECT count(*) FROM click_event");
    }

    /** The clicked_at values of the link's events, ordered ascending, at the database's microsecond precision. */
    public List<Instant> clickedAts(String code) {
        return jdbc.query("SELECT e.clicked_at FROM click_event e JOIN short_url s ON s.id = e.short_url_id"
                + " WHERE s.short_code = ? ORDER BY e.clicked_at, e.id",
                (rs, row) -> rs.getTimestamp(1).toInstant(), code);
    }

    public int rowCount() {
        return count("SELECT count(*) FROM short_url");
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public Instant createdAt(String code) {
        return jdbc.queryForObject("SELECT created_at FROM short_url WHERE short_code = ?", Timestamp.class, code)
                .toInstant();
    }

    public int countByCode(String code) {
        return count("SELECT count(*) FROM short_url WHERE short_code = ?", code);
    }

    public int countByOriginalUrl(String originalUrl) {
        return count("SELECT count(*) FROM short_url WHERE original_url = ?", originalUrl);
    }

    public int countByCodeIgnoringCase(String code) {
        return count("SELECT count(*) FROM short_url WHERE lower(short_code) = lower(?)", code);
    }

    public int countByCodeAndOwner(String code, String owner) {
        return count("SELECT count(*) FROM short_url WHERE short_code = ? AND created_by = ?", code, owner);
    }

    public String createdBy(String code) {
        return jdbc.queryForObject("SELECT created_by FROM short_url WHERE short_code = ?", String.class, code);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
