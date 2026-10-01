package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.schwab.urlshortener.config.ExpirationProperties;
import com.schwab.urlshortener.config.ShortCodeProperties;
import com.schwab.urlshortener.repository.ClickEventRepository;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidExpirationException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.util.domain.ShortUrl;
import com.schwab.urlshortener.util.domain.ShortUrlStatus;
import com.schwab.urlshortener.util.domain.exception.ShortUrlAlreadyActiveException;
import com.schwab.urlshortener.util.shortcode.ShortCodeGenerator;
import com.schwab.urlshortener.util.validation.AliasPolicy;
import com.schwab.urlshortener.util.validation.UrlValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** US-016: expiry on create and PATCH (AC1, AC2, AC5, AC7; D107, D114, D118, D124–D127). */
class ShortUrlServiceExpiryTest {

    private static final String URL = "https://example.com/page";
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-09-30T12:00:00.123456Z");
    /** When the fixture was created; distinct from NOW_MICROS so "unchanged" assertions can fail. */
    private static final Instant EARLIER = NOW_MICROS.minusSeconds(3600);
    private static final Instant EXPIRY = Instant.parse("2026-12-31T00:00:00Z");
    private static final Instant NEW_EXPIRY = Instant.parse("2027-06-30T00:00:00Z");
    private static final Caller ALICE = new Caller("alice", false);
    private static final Caller BOB = new Caller("bob", false);

    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final ShortCodeGenerator generator = mock(ShortCodeGenerator.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        service = new ShortUrlService(repository, mock(ClickEventRepository.class), generator,
                new UrlValidator("https://short.example"), new AliasPolicy(List.of()),
                new ExpirationPolicy(new ExpirationProperties(Period.ofYears(10))), Clock.fixed(NOW, ZoneOffset.UTC),
                new ShortCodeProperties(7, 5), transactionManager);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(repository.saveAndFlush(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));
        when(generator.generate()).thenReturn("Gen1234");
    }

    private ShortUrl stored(String code, Instant expiresAt, ShortUrlStatus status) {
        ShortUrl url = ShortUrl.create(code, URL, false, "alice", EARLIER, expiresAt);
        if (status == ShortUrlStatus.DEACTIVATED) {
            url.deactivate(EARLIER);
        }
        when(repository.findByShortCode(code)).thenReturn(Optional.of(url));
        return url;
    }

    private ShortUrl saved() {
        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    // ---- create (AC1, AC2)

    @Test
    void shouldStoreTheValidatedExpiryTruncatedToMicrosecondsOnCreate() {
        ShortUrlView view = service.create(new CreateShortUrlCommand(URL, "promo2026", "alice",
                EXPIRY.plusNanos(789)));

        assertThat(saved().getExpiresAt()).isEqualTo(EXPIRY);
        assertThat(view.expiresAt()).isEqualTo(EXPIRY);
        assertThat(view.expired()).isFalse();
    }

    @Test
    void shouldCreateANeverExpiringLinkWithoutAnExpiry() {
        ShortUrlView view = service.create(new CreateShortUrlCommand(URL, null, "alice"));

        assertThat(saved().getExpiresAt()).isNull();
        assertThat(view.expiresAt()).isNull();
        assertThat(view.expired()).isFalse();
    }

    @Test
    void shouldRejectAPastOrPresentExpiryOnCreateBeforeAnyInsert() {
        for (Instant bad : List.of(NOW_MICROS.minusSeconds(1), NOW_MICROS, NOW_MICROS.plusNanos(500))) {
            assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, null, "alice", bad)))
                    .isExactlyInstanceOf(InvalidExpirationException.class);
        }
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(generator);
    }

    @Test
    void shouldCheckTheUrlThenTheAliasBeforeTheExpiry() {
        Instant past = NOW.minusSeconds(60);

        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand("ftp://x", "promo2026", "alice", past)))
                .isExactlyInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "no", "alice", past)))
                .isExactlyInstanceOf(InvalidAliasException.class);
        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "promo2026", "alice", past)))
                .isExactlyInstanceOf(InvalidExpirationException.class);
    }

    // ---- PATCH (AC5)

    @Test
    void shouldSetAnExpiryAndWriteOnce() {
        ShortUrl url = stored("Abc1234", null, ShortUrlStatus.ACTIVE);

        ShortUrlView view = service.update("Abc1234", new UpdateShortUrlCommand(null, true, NEW_EXPIRY), ALICE);

        assertThat(url.getExpiresAt()).isEqualTo(NEW_EXPIRY);
        assertThat(url.getUpdatedAt()).isEqualTo(NOW_MICROS);
        assertThat(view.expiresAt()).isEqualTo(NEW_EXPIRY);
        verify(repository).flush();
    }

    @Test
    void shouldClearTheExpiryWithAnExplicitNull() {
        ShortUrl url = stored("Abc1234", EXPIRY, ShortUrlStatus.ACTIVE);

        ShortUrlView view = service.update("Abc1234", new UpdateShortUrlCommand(null, true, null), ALICE);

        assertThat(url.getExpiresAt()).isNull();
        assertThat(view.expiresAt()).isNull();
        verify(repository).flush();
    }

    @Test
    void shouldLeaveTheExpiryUnchangedWhenOnlyActiveIsSent() {
        ShortUrl url = stored("Abc1234", EXPIRY, ShortUrlStatus.ACTIVE);

        service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE);

        assertThat(url.getExpiresAt()).isEqualTo(EXPIRY);
        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DEACTIVATED);
    }

    @Test
    void shouldWriteNothingWhenTheExpiryIsAlreadyTheRequestedValue() {
        ShortUrl url = stored("Abc1234", EXPIRY, ShortUrlStatus.ACTIVE);

        ShortUrlView view = service.update("Abc1234", new UpdateShortUrlCommand(null, true, EXPIRY), ALICE);

        assertThat(view.expiresAt()).isEqualTo(EXPIRY);
        assertThat(url.getUpdatedAt()).isEqualTo(EARLIER);
        verify(repository, never()).flush();
    }

    @Test
    void shouldApplyNothingWhenAMixedRequestHasARedundantActive() {
        ShortUrl url = stored("Abc1234", EXPIRY, ShortUrlStatus.ACTIVE);

        assertThatThrownBy(() -> service.update("Abc1234", new UpdateShortUrlCommand(true, true, NEW_EXPIRY), ALICE))
                .isExactlyInstanceOf(ShortUrlAlreadyActiveException.class);

        assertThat(url.getExpiresAt()).isEqualTo(EXPIRY);
        assertThat(url.getUpdatedAt()).isEqualTo(EARLIER);
        verify(repository, never()).flush();
    }

    @Test
    void shouldApplyBothChangesTogether() {
        ShortUrl url = stored("Abc1234", EXPIRY, ShortUrlStatus.DEACTIVATED);

        service.update("Abc1234", new UpdateShortUrlCommand(true, true, NEW_EXPIRY), ALICE);

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getExpiresAt()).isEqualTo(NEW_EXPIRY);
        verify(repository).flush();
    }

    @Test
    void shouldRejectAPastExpiryBeforeAnyLookup() {
        assertThatThrownBy(() -> service.update("Abc1234",
                new UpdateShortUrlCommand(null, true, NOW.minusSeconds(1)), BOB))
                .isExactlyInstanceOf(InvalidExpirationException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void shouldReturnNotFoundToANonOwnerWithAValidExpiry() {
        ShortUrl url = stored("Abc1234", EXPIRY, ShortUrlStatus.ACTIVE);

        assertThatThrownBy(() -> service.update("Abc1234", new UpdateShortUrlCommand(null, true, null), BOB))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);

        assertThat(url.getExpiresAt()).isEqualTo(EXPIRY);
    }

    // ---- representation (AC7)

    @Test
    void shouldReportAnExpiredLinkToItsOwnerWithExpiredTrue() {
        stored("Abc1234", NOW_MICROS.minusSeconds(1), ShortUrlStatus.ACTIVE);

        ShortUrlView view = service.get("Abc1234", ALICE);

        assertThat(view.expired()).isTrue();
        assertThat(view.expiresAt()).isEqualTo(NOW_MICROS.minusSeconds(1));
    }

    @Test
    void shouldReviveAnExpiredLinkByExtendingIt() {
        ShortUrl url = stored("Abc1234", NOW_MICROS.minusSeconds(1), ShortUrlStatus.ACTIVE);

        ShortUrlView view = service.update("Abc1234", new UpdateShortUrlCommand(null, true, NEW_EXPIRY), ALICE);

        assertThat(view.expired()).isFalse();
        assertThat(url.isExpiredAt(NOW)).isFalse();
    }
}
