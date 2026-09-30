package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.domain.ShortUrl;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code findByShortCode} applies no status filtering (AC8): it returns the row in any status,
 * and service-layer callers decide what to do with {@code DEACTIVATED}/{@code DELETED} rows. The
 * derived query uses the {@code uk_short_url_short_code} unique index.
 */
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    /** The only constraint whose violation means "code already taken" (V1 schema). */
    String SHORT_CODE_UNIQUE_CONSTRAINT = "uk_short_url_short_code";

    Optional<ShortUrl> findByShortCode(String shortCode);

    /**
     * D16, D27: the only writer of click_count/last_accessed_at. Never touches version or updated_at. D94:
     * GREATEST keeps last_accessed_at at the latest click time when clicks commit out of order, and ignores NULL, so
     * the first click still sets it.
     */
    String RECORD_CLICK_SQL = "UPDATE short_url SET click_count = click_count + 1,"
            + " last_accessed_at = GREATEST(last_accessed_at, :clickedAt) WHERE id = :id AND status = 'ACTIVE'";

    /**
     * Atomically counts one click (D91: only while the row is still ACTIVE). Carries no transaction of its own: it
     * must run in the caller's transaction, which {@code JpaClickRecorder} owns. {@code clickedAt} must already be
     * truncated to microseconds (D45).
     *
     * @return 1 if the click was counted, 0 if the row is unknown or no longer ACTIVE
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = RECORD_CLICK_SQL, nativeQuery = true)
    int recordClick(@Param("id") long id, @Param("clickedAt") Instant clickedAt);
}
