package com.schwab.urlshortener.domain;

import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyActiveException;
import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyDeactivatedException;
import com.schwab.urlshortener.domain.exception.ShortUrlDeletedException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Persistent short URL. Entities never leave the service layer (CLAUDE.md layering rule).
 *
 * <p>{@code click_count}/{@code last_accessed_at} are {@code insertable = false, updatable =
 * false} (D27): they are written only by US-010's atomic click {@code UPDATE}. Creation-time
 * columns ({@code short_code}, {@code original_url}, {@code custom_alias}, {@code created_by},
 * {@code created_at}) are {@code updatable = false}: codes are immutable and never reused (D1).
 *
 * <p>Timestamps are owned by the application through an injected {@link java.time.Clock} (D45):
 * callers pass an {@link Instant} into {@link #create} and every transition method. The DB {@code
 * DEFAULT now()} values in V1 serve only raw SQL inserts.
 */
@Entity
@Table(name = "short_url")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(onlyExplicitlyIncluded = true)
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @Column(name = "short_code", nullable = false, updatable = false, length = 32)
    @ToString.Include
    private String shortCode;

    @Column(name = "original_url", nullable = false, updatable = false, length = 2048)
    private String originalUrl;

    @Column(name = "custom_alias", nullable = false, updatable = false)
    private boolean customAlias;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @ToString.Include
    private ShortUrlStatus status;

    /** D27: written only by the atomic click UPDATE (US-010). Omitted from INSERT, so the DB default 0 applies. */
    @Column(name = "click_count", nullable = false, insertable = false, updatable = false)
    private long clickCount;

    /** D27: written only by the atomic click UPDATE (US-010). */
    @Column(name = "last_accessed_at", insertable = false, updatable = false)
    private Instant lastAccessedAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    @Version
    @Column(name = "version", nullable = false)
    @ToString.Include
    private Long version;

    public static ShortUrl create(String shortCode, String originalUrl, boolean customAlias,
                                   String createdBy, Instant createdAt) {
        ShortUrl url = new ShortUrl();
        url.shortCode = Objects.requireNonNull(shortCode, "shortCode");
        url.originalUrl = Objects.requireNonNull(originalUrl, "originalUrl");
        url.customAlias = customAlias;
        url.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        url.createdAt = toDbPrecision(Objects.requireNonNull(createdAt, "createdAt"));
        url.updatedAt = url.createdAt;
        url.status = ShortUrlStatus.ACTIVE;
        url.clickCount = 0L;        // mirrors DEFAULT 0 (column not inserted)
        url.lastAccessedAt = null;  // mirrors NULL (column not inserted)
        return url;                 // id == null, version == null -> Spring Data treats it as new
    }

    public void deactivate(Instant at) {
        Instant now = toDbPrecision(Objects.requireNonNull(at, "at"));
        requireNotDeleted();
        if (status == ShortUrlStatus.DEACTIVATED) {
            throw new ShortUrlAlreadyDeactivatedException(shortCode);
        }
        status = ShortUrlStatus.DEACTIVATED;
        updatedAt = now;
    }

    public void reactivate(Instant at) {
        Instant now = toDbPrecision(Objects.requireNonNull(at, "at"));
        requireNotDeleted();
        if (status == ShortUrlStatus.ACTIVE) {
            throw new ShortUrlAlreadyActiveException(shortCode);
        }
        status = ShortUrlStatus.ACTIVE;
        updatedAt = now;
    }

    public void softDelete(String deletedBy, Instant deletedAt) {
        Objects.requireNonNull(deletedBy, "deletedBy");
        Instant now = toDbPrecision(Objects.requireNonNull(deletedAt, "deletedAt"));
        requireNotDeleted();
        status = ShortUrlStatus.DELETED;   // allowed from ACTIVE and DEACTIVATED (D36)
        this.deletedBy = deletedBy;
        this.deletedAt = now;
        updatedAt = now;
    }

    private void requireNotDeleted() {
        if (status == ShortUrlStatus.DELETED) {
            throw new ShortUrlDeletedException(shortCode);
        }
    }

    /** timestamptz has microsecond resolution; truncate so the in-memory value equals the stored value. */
    private static Instant toDbPrecision(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
