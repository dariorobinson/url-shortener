package com.schwab.urlshortener.entity;

import com.schwab.urlshortener.exception.ShortUrlAlreadyActiveException;
import com.schwab.urlshortener.exception.ShortUrlAlreadyDeactivatedException;
import com.schwab.urlshortener.exception.ShortUrlDeletedException;
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

    /**
     * Maximum length, in code points, of the actor stored in {@code created_by}/{@code deleted_by}
     * (D51). Shared by the column definition, the actor guard and the configured-username bound.
     */
    public static final int MAX_ACTOR_LENGTH = 100;

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

    @Column(name = "created_by", nullable = false, updatable = false, length = MAX_ACTOR_LENGTH)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = MAX_ACTOR_LENGTH)
    private String deletedBy;

    /** D106, D112: null means the link never expires; expiry is computed from this column, never stored as a status. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    @ToString.Include
    private Long version;

    /**
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code createdBy} is blank, has leading or trailing
     *         whitespace, or exceeds {@link #MAX_ACTOR_LENGTH} code points (D51)
     */
    public static ShortUrl create(String shortCode, String originalUrl, boolean customAlias,
                                   String createdBy, Instant createdAt) {
        return create(shortCode, originalUrl, customAlias, createdBy, createdAt, null);
    }

    /**
     * Creates a link that expires at {@code expiresAt} (D107), or never when it is null. The caller has already
     * validated the expiry against the clock and the configured horizon; the database CHECK
     * {@code ck_short_url_expires_after_created} is the final guarantee that it follows {@code createdAt}.
     *
     * @throws NullPointerException if any argument other than {@code expiresAt} is null
     * @throws IllegalArgumentException if {@code createdBy} is blank, padded or too long
     */
    public static ShortUrl create(String shortCode, String originalUrl, boolean customAlias,
                                   String createdBy, Instant createdAt, Instant expiresAt) {
        ShortUrl url = new ShortUrl();
        url.expiresAt = expiresAt == null ? null : toDbPrecision(expiresAt);
        url.shortCode = Objects.requireNonNull(shortCode, "shortCode");
        url.originalUrl = Objects.requireNonNull(originalUrl, "originalUrl");
        url.customAlias = customAlias;
        Objects.requireNonNull(createdBy, "createdBy");
        // Computed before the actor guard so that a null createdAt is reported as NPE, not masked by the guard.
        Instant created = toDbPrecision(Objects.requireNonNull(createdAt, "createdAt"));
        url.createdBy = requireValidActor(createdBy, "createdBy");
        url.createdAt = created;
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

    /**
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code deletedBy} is blank, has leading or trailing
     *         whitespace, or exceeds {@link #MAX_ACTOR_LENGTH} code points (D51)
     * @throws ShortUrlDeletedException if this short URL is already deleted
     */
    public void softDelete(String deletedBy, Instant deletedAt) {
        Objects.requireNonNull(deletedBy, "deletedBy");
        Instant now = toDbPrecision(Objects.requireNonNull(deletedAt, "deletedAt"));
        requireValidActor(deletedBy, "deletedBy");
        requireNotDeleted();
        status = ShortUrlStatus.DELETED;   // allowed from ACTIVE and DEACTIVATED (D36)
        this.deletedBy = deletedBy;
        this.deletedAt = now;
        updatedAt = now;
    }

    /**
     * PostgreSQL silently truncates trailing spaces that overflow a VARCHAR(n), so the bound is
     * enforced here before insert (D47, D51). Length is in code points, matching {@code char_length}.
     */
    /**
     * D111: a link is expired from the instant {@code expiresAt} itself onwards. Never true when no expiry is set.
     *
     * @throws NullPointerException if {@code now} is null
     */
    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * Sets, changes or clears ({@code null}) the expiry (D114). Setting the value the link already has is not a
     * change (D125): nothing is modified and {@code false} is returned, so no UPDATE is written.
     *
     * @return true if the expiry changed
     * @throws NullPointerException if {@code at} is null
     * @throws ShortUrlDeletedException if the link is DELETED (D46)
     */
    public boolean changeExpiry(Instant newExpiry, Instant at) {
        Instant now = toDbPrecision(Objects.requireNonNull(at, "at"));
        requireNotDeleted();
        Instant target = newExpiry == null ? null : toDbPrecision(newExpiry);
        if (Objects.equals(target, expiresAt)) {
            return false;
        }
        expiresAt = target;
        updatedAt = now;
        return true;
    }

    private static String requireValidActor(String actor, String name) {
        if (actor.isBlank() || !actor.equals(actor.strip())
                || actor.codePointCount(0, actor.length()) > MAX_ACTOR_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must be non-blank, without surrounding whitespace, and at most "
                            + MAX_ACTOR_LENGTH + " characters");
        }
        return actor;
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
