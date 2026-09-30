package com.schwab.urlshortener.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Arranges and inspects {@code short_url} rows for integration tests when the API cannot express
 * it (seeding colliding rows, counting rows by marker). Rows seeded here are owned by
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
        jdbc.execute("TRUNCATE TABLE short_url");
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
