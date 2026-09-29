package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.support.PostgresErrors;
import com.schwab.urlshortener.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Raw-SQL constraint tests (AC2-AC5, D1, D6, D47). Inserts go through {@link JdbcTemplate}
 * directly, bypassing the entity, so they hit the database constraint rather than an application
 * check. Each test performs exactly one failing statement, and it is the last DB action in the
 * test, because PostgreSQL aborts the transaction after an error.
 */
@RepositoryTest
class ShortUrlConstraintsTest {

    private static final String DEFAULT_URL = "https://example.com/";
    private static final OffsetDateTime DELETED_AT = OffsetDateTime.parse("2026-01-01T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int insertShortUrl(String shortCode, String originalUrl, String status, long clickCount,
                                OffsetDateTime deletedAt, String deletedBy) {
        return jdbcTemplate.update("""
                INSERT INTO short_url (short_code, original_url, created_by, status, click_count, deleted_at, deleted_by)
                VALUES (?, ?, 'alice', ?, ?, ?, ?)
                """, shortCode, originalUrl, status, clickCount, deletedAt, deletedBy);
    }

    private int insertShortUrl(String shortCode, String status, long clickCount,
                                OffsetDateTime deletedAt, String deletedBy) {
        return insertShortUrl(shortCode, DEFAULT_URL, status, clickCount, deletedAt, deletedBy);
    }

    private int insertShortUrl(String shortCode, String status) {
        return insertShortUrl(shortCode, status, 0, null, null);
    }

    private int insertShortUrl(String shortCode) {
        return insertShortUrl(shortCode, "ACTIVE");
    }

    /** Builds a URL of exactly {@code length} characters, ending in {@code last} (D47). */
    private static String urlOfLength(int length, char last) {
        return DEFAULT_URL + "a".repeat(length - DEFAULT_URL.length() - 1) + last;
    }

    private static void assertViolatesConstraint(Throwable thrown, String sqlState, String constraintName) {
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(PostgresErrors.sqlState(thrown)).isEqualTo(sqlState);
        assertThat(PostgresErrors.constraintName(thrown)).isEqualTo(constraintName);
    }

    static Stream<String> invalidShortCodes() {
        return Stream.of(
                "ab",                     // 2 chars, below the 3-char minimum
                "a".repeat(33),           // 33 chars, above the 32-char maximum (D47: TEXT + CHECK)
                "abc-def",
                "abc_def",
                "abc def",
                "abcé",
                "straße",
                "abc\n",
                "");
    }

    @ParameterizedTest
    @MethodSource("invalidShortCodes")
    void shouldRejectShortCodeNotMatchingFormat(String shortCode) {
        assertThatThrownBy(() -> insertShortUrl(shortCode))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_code_format"));
    }

    /**
     * D47: short_code is TEXT, not VARCHAR(32), so PostgreSQL never truncates a trailing space.
     * Under the old VARCHAR(32) column this 33-character value (32 valid characters plus one
     * trailing space) was silently stored truncated to 32 characters. The CHECK now sees the
     * value exactly as submitted and rejects it.
     */
    @Test
    void shouldRejectMaxLengthShortCodeWithTrailingSpaceInsteadOfTruncating() {
        String shortCode = "A1".repeat(16) + " ";
        assertThat(shortCode).hasSize(33);

        assertThatThrownBy(() -> insertShortUrl(shortCode))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_code_format"));
    }

    static Stream<Arguments> shortCodesAtLengthBoundaries() {
        return Stream.of(
                Arguments.of("abc", 3),
                Arguments.of("A1".repeat(16), 32));
    }

    @ParameterizedTest
    @MethodSource("shortCodesAtLengthBoundaries")
    void shouldAcceptShortCodeAtLengthBoundaries(String shortCode, int expectedLength) {
        assertThat(shortCode).hasSize(expectedLength);

        int inserted = insertShortUrl(shortCode);

        assertThat(inserted).isEqualTo(1);
    }

    static Stream<Arguments> oversizeOriginalUrls() {
        return Stream.of(
                Arguments.of(urlOfLength(2049, 'a')),
                Arguments.of(urlOfLength(2049, ' ')));
    }

    @ParameterizedTest
    @MethodSource("oversizeOriginalUrls")
    void shouldRejectOriginalUrlLongerThan2048Characters(String originalUrl) {
        assertThat(originalUrl).hasSize(2049);

        assertThatThrownBy(() -> insertShortUrl("abc1234", originalUrl, "ACTIVE", 0, null, null))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_original_url_length"));
    }

    static Stream<Arguments> boundaryOriginalUrls() {
        return Stream.of(
                Arguments.of(urlOfLength(2048, 'a')),
                Arguments.of(urlOfLength(2048, ' ')));
    }

    @ParameterizedTest
    @MethodSource("boundaryOriginalUrls")
    void shouldAcceptOriginalUrlAtLengthBoundary(String originalUrl) {
        assertThat(originalUrl).hasSize(2048);

        int inserted = insertShortUrl("abc1234", originalUrl, "ACTIVE", 0, null, null);
        assertThat(inserted).isEqualTo(1);

        Object[] row = jdbcTemplate.queryForObject(
                "SELECT original_url, char_length(original_url) FROM short_url WHERE short_code = ?",
                (rs, rowNum) -> new Object[] {rs.getString(1), rs.getInt(2)},
                "abc1234");

        assertThat(row[0]).isEqualTo(originalUrl);
        assertThat(row[1]).isEqualTo(2048);
    }

    @Test
    void shouldMeasureOriginalUrlLengthInCharactersNotBytes() {
        String originalUrl = DEFAULT_URL + "é".repeat(2028);
        assertThat(originalUrl).hasSize(2048);

        int inserted = insertShortUrl("abc1234", originalUrl, "ACTIVE", 0, null, null);
        assertThat(inserted).isEqualTo(1);

        Object[] row = jdbcTemplate.queryForObject(
                "SELECT char_length(original_url), octet_length(original_url) FROM short_url WHERE short_code = ?",
                (rs, rowNum) -> new Object[] {rs.getInt(1), rs.getInt(2)},
                "abc1234");

        assertThat(row[0]).isEqualTo(2048);
        assertThat((Integer) row[1]).isGreaterThan(2048);
    }

    static Stream<Arguments> incompleteDeletedAuditFields() {
        return Stream.of(
                Arguments.of(null, "admin"),
                Arguments.of(DELETED_AT, null),
                Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("incompleteDeletedAuditFields")
    void shouldRejectDeletedRowWithoutCompleteAuditFields(OffsetDateTime deletedAt, String deletedBy) {
        assertThatThrownBy(() -> insertShortUrl("abc1234", "DELETED", 0, deletedAt, deletedBy))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_deleted_consistency"));
    }

    @Test
    void shouldRejectNonDeletedRowWithAuditFields() {
        assertThatThrownBy(() -> insertShortUrl("abc1234", "ACTIVE", 0, DELETED_AT, "admin"))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_deleted_consistency"));
    }

    @Test
    void shouldAcceptDeletedRowWithCompleteAuditFields() {
        int inserted = insertShortUrl("abc1234", "DELETED", 0, DELETED_AT, "admin");

        assertThat(inserted).isEqualTo(1);
    }

    static Stream<Arguments> partialAuditFieldsOnNonDeletedRow() {
        return Stream.of(
                Arguments.of(DELETED_AT, null),
                Arguments.of(null, "admin"));
    }

    /** D44: the tightened CHECK also rejects a non-deleted row that carries only one audit field. */
    @ParameterizedTest
    @MethodSource("partialAuditFieldsOnNonDeletedRow")
    void shouldRejectNonDeletedRowWithPartialAuditFields(OffsetDateTime deletedAt, String deletedBy) {
        assertThatThrownBy(() -> insertShortUrl("abc1234", "ACTIVE", 0, deletedAt, deletedBy))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_deleted_consistency"));
    }

    @Test
    void shouldRejectNegativeClickCount() {
        assertThatThrownBy(() -> insertShortUrl("abc1234", "ACTIVE", -1, null, null))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_click_count"));
    }

    static Stream<String> unknownStatuses() {
        return Stream.of("EXPIRED", "active");
    }

    @ParameterizedTest
    @MethodSource("unknownStatuses")
    void shouldRejectUnknownStatus(String status) {
        assertThatThrownBy(() -> insertShortUrl("abc1234", status))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23514", "ck_short_url_status"));
    }

    @Test
    void shouldRejectDuplicateShortCode() {
        insertShortUrl("abc1234");

        assertThatThrownBy(() -> insertShortUrl("abc1234"))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23505", "uk_short_url_short_code"));
    }

    @Test
    void shouldNotReuseShortCodeOfSoftDeletedRow() {
        insertShortUrl("abc1234", "DELETED", 0, DELETED_AT, "admin");

        assertThatThrownBy(() -> insertShortUrl("abc1234"))
                .satisfies(thrown -> assertViolatesConstraint(thrown, "23505", "uk_short_url_short_code"));
    }

    @Test
    void shouldTreatShortCodesCaseSensitively() {
        int firstInserted = insertShortUrl("AbCd123");
        int secondInserted = insertShortUrl("abcd123");

        assertThat(firstInserted).isEqualTo(1);
        assertThat(secondInserted).isEqualTo(1);
    }
}
