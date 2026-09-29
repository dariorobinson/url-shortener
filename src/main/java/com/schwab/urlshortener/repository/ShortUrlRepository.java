package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.domain.ShortUrl;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code findByShortCode} applies no status filtering (AC8): it returns the row in any status,
 * and service-layer callers decide what to do with {@code DEACTIVATED}/{@code DELETED} rows. The
 * derived query uses the {@code uk_short_url_short_code} unique index.
 */
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);
}
