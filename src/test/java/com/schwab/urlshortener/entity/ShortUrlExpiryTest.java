package com.schwab.urlshortener.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.exception.ShortUrlDeletedException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** US-016: the entity's expiry rules (D111, D114, D125, D46). */
class ShortUrlExpiryTest {

    private static final Instant CREATED = Instant.parse("2026-09-30T10:00:00.000001Z");
    private static final Instant EXPIRY = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-30T11:00:00Z");

    private static ShortUrl link(Instant expiresAt) {
        return ShortUrl.create("Abc1234", "https://example.com", false, "alice", CREATED, expiresAt);
    }

    @Test
    void shouldNeverBeExpiredWithoutAnExpiry() {
        ShortUrl url = ShortUrl.create("Abc1234", "https://example.com", false, "alice", CREATED);

        assertThat(url.getExpiresAt()).isNull();
        assertThat(url.isExpiredAt(Instant.MAX)).isFalse();
    }

    @Test
    void shouldBeExpiredFromTheExpiryInstantItselfOnwards() {
        ShortUrl url = link(EXPIRY);

        assertThat(url.isExpiredAt(EXPIRY.minusNanos(1000))).isFalse();
        assertThat(url.isExpiredAt(EXPIRY)).isTrue();
        assertThat(url.isExpiredAt(EXPIRY.plusNanos(1000))).isTrue();
    }

    @Test
    void shouldTruncateTheExpiryToMicrosecondsOnCreate() {
        assertThat(link(EXPIRY.plusNanos(999)).getExpiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    void shouldSetExtendAndClearTheExpiryAndStampUpdatedAt() {
        ShortUrl url = link(EXPIRY);

        assertThat(url.changeExpiry(EXPIRY.plusSeconds(3600), LATER)).isTrue();
        assertThat(url.getExpiresAt()).isEqualTo(EXPIRY.plusSeconds(3600));
        assertThat(url.getUpdatedAt()).isEqualTo(LATER);

        assertThat(url.changeExpiry(null, LATER.plusSeconds(1))).isTrue();
        assertThat(url.getExpiresAt()).isNull();
        assertThat(url.getUpdatedAt()).isEqualTo(LATER.plusSeconds(1));
    }

    @Test
    void shouldTreatTheSameExpiryAsNoChangeEvenWithSubMicrosecondDigits() {
        ShortUrl url = link(EXPIRY);

        assertThat(url.changeExpiry(EXPIRY.plusNanos(500), LATER)).isFalse();

        assertThat(url.getExpiresAt()).isEqualTo(EXPIRY);
        assertThat(url.getUpdatedAt()).isEqualTo(CREATED);
        // Positive control: a real change on the same entity does stamp updatedAt.
        assertThat(url.changeExpiry(EXPIRY.plusNanos(1000), LATER)).isTrue();
        assertThat(url.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void shouldTreatClearingANeverExpiringLinkAsNoChange() {
        ShortUrl url = link(null);

        assertThat(url.changeExpiry(null, LATER)).isFalse();
        assertThat(url.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void shouldAllowChangingTheExpiryOfADeactivatedLink() {
        ShortUrl url = link(EXPIRY);
        url.deactivate(LATER);

        assertThat(url.changeExpiry(null, LATER.plusSeconds(1))).isTrue();
        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DEACTIVATED);
    }

    @Test
    void shouldRefuseToChangeTheExpiryOfADeletedLinkAndChangeNothing() {
        ShortUrl url = link(EXPIRY);
        url.softDelete("admin", LATER);

        assertThatThrownBy(() -> url.changeExpiry(null, LATER.plusSeconds(1)))
                .isExactlyInstanceOf(ShortUrlDeletedException.class);

        assertThat(url.getExpiresAt()).isEqualTo(EXPIRY);
        assertThat(url.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void shouldRejectNullInstants() {
        ShortUrl url = link(EXPIRY);

        assertThatThrownBy(() -> url.isExpiredAt(null)).isExactlyInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> url.changeExpiry(null, null)).isExactlyInstanceOf(NullPointerException.class);
        assertThat(url.getExpiresAt()).isEqualTo(EXPIRY);
    }
}
