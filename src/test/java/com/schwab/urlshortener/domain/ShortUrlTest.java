package com.schwab.urlshortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyActiveException;
import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyDeactivatedException;
import com.schwab.urlshortener.domain.exception.ShortUrlDeletedException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Plain unit test, no Spring: proves {@link ShortUrl}'s state-transition invariants. */
class ShortUrlTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-03T10:00:00Z");

    private static ShortUrl newActive() {
        return ShortUrl.create("abc1234", "https://example.com/", false, "alice", CREATED_AT);
    }

    @Test
    void shouldCreateActiveShortUrlWithCreationTimestamps() {
        ShortUrl url = newActive();

        assertThat(url.getId()).isNull();
        assertThat(url.getVersion()).isNull();
        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getClickCount()).isZero();
        assertThat(url.getLastAccessedAt()).isNull();
        assertThat(url.getDeletedAt()).isNull();
        assertThat(url.getDeletedBy()).isNull();
        assertThat(url.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(url.getUpdatedAt()).isEqualTo(url.getCreatedAt());
    }

    @Test
    void shouldTruncateTimestampsToMicroseconds() {
        Instant withNanos = Instant.parse("2026-01-01T10:00:00.123456789Z");
        Instant truncated = Instant.parse("2026-01-01T10:00:00.123456Z");

        ShortUrl url = ShortUrl.create("abc1234", "https://example.com/", false, "alice", withNanos);
        assertThat(url.getCreatedAt()).isEqualTo(truncated);
        assertThat(url.getUpdatedAt()).isEqualTo(truncated);

        url.deactivate(withNanos);
        assertThat(url.getUpdatedAt()).isEqualTo(truncated);

        url.reactivate(withNanos);
        assertThat(url.getUpdatedAt()).isEqualTo(truncated);

        url.softDelete("admin", withNanos);
        assertThat(url.getUpdatedAt()).isEqualTo(truncated);
        assertThat(url.getDeletedAt()).isEqualTo(truncated);
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> ShortUrl.create(null, "https://example.com/", false, "alice", CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShortUrl.create("abc1234", null, false, "alice", CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShortUrl.create("abc1234", "https://example.com/", false, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShortUrl.create("abc1234", "https://example.com/", false, "alice", null))
                .isInstanceOf(NullPointerException.class);

        ShortUrl url = newActive();
        assertThatThrownBy(() -> url.deactivate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> url.reactivate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> url.softDelete(null, T1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> url.softDelete("admin", null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * The fixed check order puts null checks (programming errors) ahead of the DELETED check, so
     * a null argument on a DELETED entity must still throw NullPointerException, not
     * ShortUrlDeletedException, and must leave the entity's state untouched.
     */
    @Test
    void shouldRejectNullArgumentsBeforeCheckingDeletedStatus() {
        ShortUrl deleted = newActive();
        deleted.softDelete("admin", T1);

        assertThatThrownBy(() -> deleted.deactivate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> deleted.reactivate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> deleted.softDelete(null, T2)).isInstanceOf(NullPointerException.class);

        assertThat(deleted.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(deleted.getDeletedBy()).isEqualTo("admin");
        assertThat(deleted.getDeletedAt()).isEqualTo(T1);
        assertThat(deleted.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void shouldDeactivateActiveShortUrl() {
        ShortUrl url = newActive();

        url.deactivate(T1);

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DEACTIVATED);
        assertThat(url.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void shouldThrowAlreadyDeactivatedWhenDeactivatingDeactivatedShortUrl() {
        ShortUrl url = newActive();
        url.deactivate(T1);

        assertThatThrownBy(() -> url.deactivate(T2))
                .isInstanceOf(ShortUrlAlreadyDeactivatedException.class)
                .satisfies(ex -> assertThat(((ShortUrlAlreadyDeactivatedException) ex).getShortCode())
                        .isEqualTo("abc1234"));

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DEACTIVATED);
        assertThat(url.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void shouldReactivateDeactivatedShortUrl() {
        ShortUrl url = newActive();
        url.deactivate(T1);

        url.reactivate(T2);

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    void shouldThrowAlreadyActiveWhenReactivatingActiveShortUrl() {
        ShortUrl url = newActive();

        assertThatThrownBy(() -> url.reactivate(T1))
                .isInstanceOf(ShortUrlAlreadyActiveException.class)
                .satisfies(ex -> assertThat(((ShortUrlAlreadyActiveException) ex).getShortCode())
                        .isEqualTo("abc1234"));

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldSoftDeleteActiveShortUrl() {
        ShortUrl url = newActive();

        url.softDelete("admin", T1);

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(url.getDeletedBy()).isEqualTo("admin");
        assertThat(url.getDeletedAt()).isEqualTo(T1);
        assertThat(url.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void shouldSoftDeleteDeactivatedShortUrl() {
        ShortUrl url = newActive();
        url.deactivate(T1);

        url.softDelete("admin", T2);

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(url.getDeletedBy()).isEqualTo("admin");
        assertThat(url.getDeletedAt()).isEqualTo(T2);
        assertThat(url.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    void shouldKeepOriginalAuditFieldsWhenSoftDeletingTwice() {
        ShortUrl url = newActive();
        url.softDelete("admin", T1);

        assertThatThrownBy(() -> url.softDelete("bob", T2))
                .isInstanceOf(ShortUrlDeletedException.class);

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(url.getDeletedBy()).isEqualTo("admin");
        assertThat(url.getDeletedAt()).isEqualTo(T1);
        assertThat(url.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void shouldThrowDeletedWhenDeactivatingOrReactivatingDeletedShortUrl() {
        ShortUrl deletedFromActive = newActive();
        deletedFromActive.softDelete("admin", T1);

        assertThatThrownBy(() -> deletedFromActive.deactivate(T2))
                .isInstanceOf(ShortUrlDeletedException.class)
                .satisfies(ex -> assertThat(((ShortUrlDeletedException) ex).getShortCode())
                        .isEqualTo("abc1234"));
        assertThat(deletedFromActive.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(deletedFromActive.getUpdatedAt()).isEqualTo(T1);

        assertThatThrownBy(() -> deletedFromActive.reactivate(T2))
                .isInstanceOf(ShortUrlDeletedException.class);
        assertThat(deletedFromActive.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(deletedFromActive.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void shouldExcludeOriginalUrlAndUsernamesFromToString() {
        ShortUrl url = ShortUrl.create("abc1234", "https://example.com/secret?token=shh", false,
                "alice", CREATED_AT);
        url.softDelete("bob", T1);

        String toString = url.toString();

        assertThat(toString).contains("abc1234");
        assertThat(toString).contains("DELETED");
        assertThat(toString).doesNotContain("example.com");
        assertThat(toString).doesNotContain("shh");
        assertThat(toString).doesNotContain("alice");
        assertThat(toString).doesNotContain("bob");
    }

    // D51: the actor guard, shared bound ShortUrl.MAX_ACTOR_LENGTH.

    @Test
    void shouldAcceptActorAtExactlyMaxLengthOnCreateAndSoftDelete() {
        String actor = "a".repeat(ShortUrl.MAX_ACTOR_LENGTH);

        ShortUrl url = ShortUrl.create("abc1234", "https://example.com/", false, actor, CREATED_AT);
        url.softDelete(actor, T1);

        assertThat(ShortUrl.MAX_ACTOR_LENGTH).isEqualTo(100);
        assertThat(url.getCreatedBy()).isEqualTo(actor);
        assertThat(url.getDeletedBy()).isEqualTo(actor);
    }

    @Test
    void shouldCountCodePointsSoHundredMultibyteActorIsAcceptedAndHundredAndOneRejected() {
        String hundred = "\u00e9".repeat(ShortUrl.MAX_ACTOR_LENGTH);
        String tooLong = hundred + "\u00e9";

        ShortUrl url = ShortUrl.create("abc1234", "https://example.com/", false, hundred, CREATED_AT);
        url.softDelete(hundred, T1);

        assertThat(url.getCreatedBy()).isEqualTo(hundred);
        assertThat(url.getDeletedBy()).isEqualTo(hundred);
        assertThatThrownBy(() -> ShortUrl.create("abc1234", "https://example.com/", false, tooLong, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newActive().softDelete(tooLong, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCountSupplementaryCharactersAsOneCodePoint() {
        String hundred = "\uD83D\uDE00".repeat(ShortUrl.MAX_ACTOR_LENGTH);

        ShortUrl url = ShortUrl.create("abc1234", "https://example.com/", false, hundred, CREATED_AT);

        assertThat(url.getCreatedBy()).isEqualTo(hundred);
    }

    @Test
    void shouldRejectHundredAndOneSupplementaryCharacterActor() {
        String tooLong = "\uD83D\uDE00".repeat(ShortUrl.MAX_ACTOR_LENGTH + 1);

        assertThatThrownBy(() -> ShortUrl.create("abc1234", "https://example.com/", false, tooLong, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newActive().softDelete(tooLong, T1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", " alice", "alice ", "alice\n"})
    void shouldRejectBlankOrPaddedActorOnCreateAndSoftDelete(String actor) {
        assertThatThrownBy(() -> ShortUrl.create("abc1234", "https://example.com/", false, actor, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        ShortUrl url = newActive();
        assertThatThrownBy(() -> url.softDelete(actor, T1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getDeletedBy()).isNull();
        assertThat(url.getDeletedAt()).isNull();
    }

    @Test
    void shouldRejectActorOfMaxLengthPlusOneAndMaxLengthPlusTrailingSpace() {
        String plusOne = "a".repeat(ShortUrl.MAX_ACTOR_LENGTH + 1);
        String plusSpace = "a".repeat(ShortUrl.MAX_ACTOR_LENGTH) + " ";
        ShortUrl url = newActive();

        for (String actor : new String[] {plusOne, plusSpace}) {
            assertThatThrownBy(() -> ShortUrl.create("abc1234", "https://example.com/", false, actor, CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> url.softDelete(actor, T1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getDeletedBy()).isNull();
    }

    @Test
    void shouldValidateActorBeforeDeletedCheckButAfterNullChecks() {
        ShortUrl deleted = newActive();
        deleted.softDelete("admin", T1);

        assertThatThrownBy(() -> deleted.softDelete(" ", T2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deleted.softDelete("bob", T2)).isInstanceOf(ShortUrlDeletedException.class);
        assertThatThrownBy(() -> deleted.softDelete(" ", null)).isInstanceOf(NullPointerException.class);
        assertThat(deleted.getDeletedBy()).isEqualTo("admin");
    }
}
