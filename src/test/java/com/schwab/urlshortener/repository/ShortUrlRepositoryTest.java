package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.support.PostgresErrors;
import com.schwab.urlshortener.support.RepositoryTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code ShortUrlRepository} CRUD, lookup-by-code, and D27 mapping behaviour. AC7, AC8, AC9(a).
 */
@RepositoryTest
class ShortUrlRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-03T10:00:00Z");

    @Autowired
    private ShortUrlRepository repository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static ShortUrl newActive(String shortCode, Instant createdAt) {
        return ShortUrl.create(shortCode, "https://example.com/", false, "alice", createdAt);
    }

    /**
     * pgjdbc's plain {@code getObject(int)} does not return {@code OffsetDateTime} for a
     * {@code timestamptz} column, so timestamp columns must be read with an explicit required
     * type, not through {@code queryForMap}.
     */
    private Instant queryInstant(String column, Long id) {
        return jdbcTemplate.queryForObject(
                        "SELECT " + column + " FROM short_url WHERE id = ?", OffsetDateTime.class, id)
                .toInstant();
    }

    private <T> T queryColumn(String column, Class<T> type, Long id) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM short_url WHERE id = ?", type, id);
    }

    @Test
    void shouldPersistAndReloadAllFields() {
        ShortUrl saved = repository.saveAndFlush(newActive("abc1234", T0));
        testEntityManager.clear();

        ShortUrl reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getId()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(reloaded.getClickCount()).isZero();
        assertThat(reloaded.getLastAccessedAt()).isNull();
        assertThat(reloaded.getCreatedAt()).isEqualTo(T0);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(T0);
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(reloaded.getDeletedBy()).isNull();
        assertThat(reloaded.getVersion()).isEqualTo(0L);
    }

    @Test
    void shouldAssignVersionZeroOnInsertAndIncrementOnUpdate() {
        ShortUrl saved = repository.saveAndFlush(newActive("abc1234", T0));
        Long id = saved.getId();
        testEntityManager.clear();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM short_url WHERE id = ?", Long.class, id)).isEqualTo(0L);

        ShortUrl loaded = repository.findById(id).orElseThrow();
        loaded.deactivate(T1);
        repository.saveAndFlush(loaded);
        testEntityManager.clear();

        assertThat(queryColumn("version", Long.class, id)).isEqualTo(1L);
        assertThat(queryColumn("status", String.class, id)).isEqualTo("DEACTIVATED");
        assertThat(queryInstant("updated_at", id)).isEqualTo(T1);
    }

    @Test
    void shouldNotWriteAnalyticsColumnsOnInsert() {
        ShortUrl entity = newActive("abc1234", T0);
        ReflectionTestUtils.setField(entity, "clickCount", 7L);
        ReflectionTestUtils.setField(entity, "lastAccessedAt", T1);

        ShortUrl saved = repository.saveAndFlush(entity);

        assertThat(queryColumn("click_count", Long.class, saved.getId())).isEqualTo(0L);
        Integer lastAccessedAtCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM short_url WHERE id = ? AND last_accessed_at IS NULL",
                Integer.class, saved.getId());
        assertThat(lastAccessedAtCount).isEqualTo(1);
    }

    @Test
    void shouldNotOverwriteSqlUpdatedAnalyticsWhenEntityIsSaved() {
        ShortUrl saved = repository.saveAndFlush(newActive("abc1234", T0));
        Long id = saved.getId();
        testEntityManager.clear();

        ShortUrl loaded = repository.findByShortCode("abc1234").orElseThrow();
        assertThat(loaded.getClickCount()).isZero();

        jdbcTemplate.update("UPDATE short_url SET click_count = 5, last_accessed_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(T1, ZoneOffset.UTC), id);

        loaded.deactivate(T2);
        repository.saveAndFlush(loaded);

        assertThat(queryColumn("click_count", Long.class, id)).isEqualTo(5L);
        assertThat(queryInstant("last_accessed_at", id)).isEqualTo(T1);
        assertThat(queryColumn("status", String.class, id)).isEqualTo("DEACTIVATED");
        assertThat(queryInstant("updated_at", id)).isEqualTo(T2);
        assertThat(queryColumn("version", Long.class, id)).isEqualTo(1L);

        testEntityManager.clear();
        ShortUrl reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getClickCount()).isEqualTo(5L);
        assertThat(reloaded.getLastAccessedAt()).isEqualTo(T1);
    }

    @ParameterizedTest
    @EnumSource(value = ShortUrlStatus.class, names = {"ACTIVE", "DEACTIVATED"})
    void shouldPersistSoftDeleteWithinDeletedConsistencyCheck(ShortUrlStatus from) {
        ShortUrl entity = newActive("abc1234", T0);
        if (from == ShortUrlStatus.DEACTIVATED) {
            entity.deactivate(T1);
        }
        ShortUrl saved = repository.saveAndFlush(entity);
        Long id = saved.getId();

        saved.softDelete("admin", T2);
        repository.saveAndFlush(saved);
        testEntityManager.clear();

        ShortUrl reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(reloaded.getDeletedBy()).isEqualTo("admin");
        assertThat(reloaded.getDeletedAt()).isEqualTo(T2);
    }

    @ParameterizedTest
    @EnumSource(ShortUrlStatus.class)
    void shouldFindByShortCodeRegardlessOfStatus(ShortUrlStatus status) {
        ShortUrl entity = newActive("abc1234", T0);
        switch (status) {
            case DEACTIVATED -> entity.deactivate(T1);
            case DELETED -> entity.softDelete("admin", T1);
            case ACTIVE -> { }
        }
        repository.saveAndFlush(entity);
        testEntityManager.clear();

        Optional<ShortUrl> found = repository.findByShortCode("abc1234");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getStatus()).isEqualTo(status);
    }

    @Test
    void shouldReturnEmptyForUnknownShortCode() {
        Optional<ShortUrl> found = repository.findByShortCode("nope123");

        assertThat(found).isEmpty();
    }

    @Test
    void shouldMatchShortCodeCaseSensitively() {
        repository.saveAndFlush(newActive("AbCd123", T0));
        testEntityManager.clear();

        assertThat(repository.findByShortCode("abcd123")).isEmpty();
        assertThat(repository.findByShortCode("AbCd123")).isPresent();
    }

    @Test
    void shouldRejectDuplicateShortCodeThroughRepository() {
        repository.saveAndFlush(newActive("abc1234", T0));

        assertThatThrownBy(() -> repository.saveAndFlush(newActive("abc1234", T0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> {
                    assertThat(PostgresErrors.sqlState(thrown)).isEqualTo("23505");
                    assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("uk_short_url_short_code");
                });
    }

    // D65: ties the production constant and helper to the V1 schema (SQLSTATE and constraint name).
    @Test
    void shouldRecogniseADuplicateCodeAsTheShortCodeConflictThroughTheProductionHelper() {
        repository.saveAndFlush(newActive("abc1234", T0));

        assertThatThrownBy(() -> repository.saveAndFlush(newActive("abc1234", T0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> {
                    assertThat(PostgresErrors.sqlState(thrown)).isEqualTo("23505");
                    assertThat(PostgresErrors.constraintName(thrown))
                            .isEqualTo(ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT);
                    assertThat(PostgresServerErrors.isUniqueViolation(
                            thrown, ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT)).isTrue();
                });
    }

    // D64, D65: a CHECK violation is never mistaken for the code conflict, and its message carries no row data.
    @Test
    void shouldNotTreatACheckViolationAsAConflictAndKeepRowDataOutOfTheExceptionMessages() {
        String marker = "secret-row-marker";
        String tooLong = "https://example.com/" + marker + "a".repeat(2049);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO short_url (short_code, original_url, created_by) VALUES (?, ?, ?)",
                "chk1234", tooLong, "alice"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> {
                    assertThat(PostgresErrors.sqlState(thrown)).isEqualTo("23514");
                    assertThat(PostgresErrors.constraintName(thrown)).isEqualTo("ck_short_url_original_url_length");
                    assertThat(PostgresServerErrors.isUniqueViolation(
                            thrown, ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT)).isFalse();
                    for (Throwable t = thrown; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(marker)
                                .doesNotContain("Failing row contains");
                    }
                });
    }

    // D51: actor columns round-trip at exactly the shared limit, ASCII and multibyte.
    @ParameterizedTest
    @ValueSource(strings = {"a", "\u00e9"})
    void shouldPersistCreatedByAndDeletedByAtExactlyMaxLengthAndReadThemBackUnchanged(String unit) {
        String actor = unit.repeat(ShortUrl.MAX_ACTOR_LENGTH);
        ShortUrl entity = ShortUrl.create("abc1234", "https://example.com/", false, actor, T0);
        entity.softDelete(actor, T1);

        ShortUrl saved = repository.saveAndFlush(entity);
        testEntityManager.clear();

        assertThat(queryColumn("created_by", String.class, saved.getId())).isEqualTo(actor);
        assertThat(queryColumn("deleted_by", String.class, saved.getId())).isEqualTo(actor);
        assertThat(queryColumn("char_length(created_by)", Integer.class, saved.getId()))
                .isEqualTo(ShortUrl.MAX_ACTOR_LENGTH);
        assertThat(queryColumn("char_length(deleted_by)", Integer.class, saved.getId()))
                .isEqualTo(ShortUrl.MAX_ACTOR_LENGTH);
    }
}
