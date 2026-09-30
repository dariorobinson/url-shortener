package com.schwab.urlshortener.support;

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
        if ("DELETED".equals(status)) {
            jdbc.update("INSERT INTO short_url (short_code, original_url, status, created_by, deleted_at, deleted_by)"
                    + " VALUES (?, ?, 'DELETED', ?, now(), ?)", code, originalUrl, SEED_OWNER, SEED_OWNER);
        } else {
            jdbc.update("INSERT INTO short_url (short_code, original_url, status, created_by) VALUES (?, ?, ?, ?)",
                    code, originalUrl, status, SEED_OWNER);
        }
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
