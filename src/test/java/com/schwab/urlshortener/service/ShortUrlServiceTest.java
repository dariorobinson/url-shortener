package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.schwab.urlshortener.config.ExpirationProperties;
import com.schwab.urlshortener.config.ShortCodeProperties;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyActiveException;
import com.schwab.urlshortener.domain.exception.ShortUrlAlreadyDeactivatedException;
import com.schwab.urlshortener.repository.ClickEventRepository;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortCodeUnavailableException;
import com.schwab.urlshortener.service.exception.ShortUrlConcurrentModificationException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.shortcode.ShortCodeGenerator;
import com.schwab.urlshortener.validation.AliasPolicy;
import com.schwab.urlshortener.validation.UrlValidator;
import jakarta.persistence.OptimisticLockException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle logic (US-009 AC1-AC11, D26, D35, D36, D46, D51, D87), get logic (US-007 AC1-AC5, D4, D13, D72) and
 * create logic (AC2, AC3, AC4, AC5, AC6, AC7, AC11, AC15, D5, D29, D45, D47, D48, D60, D63, D64) against mocks: no
 * Spring context, no database.
 */
class ShortUrlServiceTest {

    private static final ExpirationPolicy EXPIRATION =
            new ExpirationPolicy(new ExpirationProperties(Period.ofYears(10)));

    private static final String URL = "https://example.com/page";
    private static final Instant NOW = Instant.parse("2026-09-29T14:03:12.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-09-29T14:03:12.123456Z");
    /** When the fixture's earlier transition happened; distinct from NOW_MICROS so "unchanged" can fail. */
    private static final Instant EARLIER = NOW_MICROS.minusSeconds(60);
    private static final String FIRST_ADMIN = "first-admin";

    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final ClickEventRepository clickEvents = mock(ClickEventRepository.class);
    private final ShortCodeGenerator generator = mock(ShortCodeGenerator.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(ShortUrlService.class);
    private Level originalLevel;

    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        service = serviceWithMaxAttempts(5);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(repository.saveAndFlush(any(ShortUrl.class))).thenAnswer(inv -> inv.getArgument(0));
        originalLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        serviceLogger.setLevel(originalLevel);
        logs.stop();
    }

    private ShortUrlService serviceWithMaxAttempts(int maxAttempts) {
        return new ShortUrlService(repository, clickEvents, generator, new UrlValidator("https://short.example"),
                new AliasPolicy(List.of()), EXPIRATION, clock,
                new ShortCodeProperties(7, maxAttempts), transactionManager);
    }

    private static CreateShortUrlCommand generated(String url) {
        return new CreateShortUrlCommand(url, null, "alice");
    }

    private static DataIntegrityViolationException violation(String sqlState, String constraint) {
        return new DataIntegrityViolationException("could not execute statement", new PSQLException(
                new ServerErrorMessage("SERROR\0C" + sqlState + "\0n" + constraint + "\0Mx\0"), true));
    }

    private static DataIntegrityViolationException codeCollision() {
        return violation("23505", ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT);
    }

    private List<ShortUrl> savedEntities() {
        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(repository, org.mockito.Mockito.atLeast(0)).saveAndFlush(captor.capture());
        return captor.getAllValues();
    }

    // ---- alias path (AC2, AC3, AC4, D60)

    @Test
    void shouldStoreTheAliasAsCodeWithCustomAliasTrueAndNeverCallTheGenerator() {
        ShortUrlView view = service.create(new CreateShortUrlCommand(URL, "promo2026", "alice"));

        assertThat(view.shortCode()).isEqualTo("promo2026");
        assertThat(view.customAlias()).isTrue();
        assertThat(view.status()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(view.clickCount()).isZero();
        assertThat(view.lastAccessedAt()).isNull();
        ShortUrl saved = savedEntities().getFirst();
        assertThat(saved.getShortCode()).isEqualTo("promo2026");
        assertThat(saved.isCustomAlias()).isTrue();
        assertThat(saved.getCreatedBy()).isEqualTo("alice");
        verifyNoInteractions(generator);
    }

    @Test
    void shouldThrowAliasAlreadyExistsAfterExactlyOneSaveWhenTheCodeConstraintIsViolated() {
        DataIntegrityViolationException collision = codeCollision();
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(collision);

        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "promo2026", "alice")))
                .isInstanceOf(AliasAlreadyExistsException.class)
                .hasCause(collision)
                .hasMessageContaining("promo2026");

        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
        verifyNoInteractions(generator);
    }

    @Test
    void shouldRethrowANonUniqueViolationOnTheAliasPathAsIsAndNotMapItToConflict() {
        DataIntegrityViolationException check = violation("23514", "ck_short_url_original_url_length");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(check);

        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "promo2026", "alice")))
                .isSameAs(check);

        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldRethrowAUniqueViolationOnAnotherConstraintOnTheAliasPath() {
        DataIntegrityViolationException other = violation("23505", "uk_something_else");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "promo2026", "alice")))
                .isSameAs(other);
    }

    @Test
    void shouldRethrowAViolationWithoutAPostgresErrorOnTheAliasPath() {
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("no driver error");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(unknown);

        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "promo2026", "alice")))
                .isSameAs(unknown);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "promo_1", "API", "Health", "", "   ", " promo2026", "promo2026 ",
            "abcdefghijklmnopqrstuvwxyzABCDEFG"})
    void shouldRejectAnInvalidAliasBeforeAnyDatabaseOrGeneratorAccess(String alias) {
        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, alias, "alice")))
                .isInstanceOf(InvalidAliasException.class);

        verifyNoInteractions(repository, generator, transactionManager);
    }

    @Test
    void shouldRejectAUrlWhoseEncodedFormExceeds2048BytesEvenWithin2048Characters() {
        String url = "https://other.example/" + "\u00e9".repeat(500); // 522 characters, 3022 encoded bytes (D84)

        assertThatThrownBy(() -> service.create(generated(url))).isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(repository, generator, transactionManager);
    }

    @Test
    void shouldNotPutTheRejectedAliasInTheInvalidAliasExceptionMessage() {
        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "bad-alias-marker!", "alice")))
                .isInstanceOf(InvalidAliasException.class)
                .hasMessageNotContaining("bad-alias-marker");
    }

    // ---- URL validation (AC5, D63)

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/x", "https://u:p@example.com/x", "https://short.example/x",
            " https://example.com", "not a url", "https://bücher.example/"})
    void shouldRejectAnInvalidUrlBeforeAnyDatabaseOrGeneratorAccess(String url) {
        assertThatThrownBy(() -> service.create(generated(url))).isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(repository, generator, transactionManager);
    }

    @Test
    void shouldReportTheInvalidUrlBeforeAnInvalidAlias() {
        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand("ftp://example.com", "x", "alice")))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void shouldNotPutTheRejectedUrlInTheInvalidUrlExceptionMessage() {
        String url = "ftp://example.com/secret-path-marker";

        assertThatThrownBy(() -> service.create(generated(url)))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageNotContaining("secret-path-marker");
    }

    // ---- generated-code path (AC6, AC7, AC15)

    @Test
    void shouldStoreTheGeneratedCodeWithCustomAliasFalse() {
        when(generator.generate()).thenReturn("Abc1234");

        ShortUrlView view = service.create(generated(URL));

        assertThat(view.shortCode()).isEqualTo("Abc1234");
        assertThat(view.customAlias()).isFalse();
        verify(generator, times(1)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldRetryInAFreshRequiresNewTransactionAfterACollisionAndThenSucceed() {
        when(generator.generate()).thenReturn("Coll1de", "Abc1234");
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenThrow(codeCollision())
                .thenAnswer(inv -> inv.getArgument(0));

        ShortUrlView view = service.create(generated(URL));

        assertThat(view.shortCode()).isEqualTo("Abc1234");
        verify(generator, times(2)).generate();
        verify(repository, times(2)).saveAndFlush(any(ShortUrl.class));
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .extracting(TransactionDefinition::getPropagationBehavior)
                .containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactionManager, times(1)).rollback(transactionStatus);
        verify(transactionManager, times(1)).commit(transactionStatus);
    }

    @Test
    void shouldUseAFreshEntityForEveryAttempt() {
        when(generator.generate()).thenReturn("Coll1de", "Abc1234");
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenThrow(codeCollision())
                .thenAnswer(inv -> inv.getArgument(0));

        service.create(generated(URL));

        List<ShortUrl> saved = savedEntities();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0)).isNotSameAs(saved.get(1));
        assertThat(saved).extracting(ShortUrl::getShortCode).containsExactly("Coll1de", "Abc1234");
    }

    @Test
    void shouldThrowShortCodeUnavailableAfterExactlyMaxAttemptsCollisionsAndCommitNothing() {
        when(generator.generate()).thenReturn("Coll1de");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(codeCollision());

        assertThatThrownBy(() -> service.create(generated(URL))).isInstanceOf(ShortCodeUnavailableException.class);

        verify(generator, times(5)).generate();
        verify(repository, times(5)).saveAndFlush(any(ShortUrl.class));
        verify(transactionManager, times(5)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void shouldHonourAMaxAttemptsOfOne() {
        ShortUrlService single = serviceWithMaxAttempts(1);
        when(generator.generate()).thenReturn("Coll1de");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(codeCollision());

        assertThatThrownBy(() -> single.create(generated(URL))).isInstanceOf(ShortCodeUnavailableException.class);

        verify(generator, times(1)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldTreatAReservedGeneratedCodeAsACollisionWithoutADatabaseRoundTrip() {
        when(generator.generate()).thenReturn("Health", "Abc1234");

        ShortUrlView view = service.create(generated(URL));

        assertThat(view.shortCode()).isEqualTo("Abc1234");
        verify(generator, times(2)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
        assertThat(savedEntities()).extracting(ShortUrl::getShortCode).containsExactly("Abc1234");
        verify(transactionManager, times(1)).getTransaction(any());
    }

    @Test
    void shouldCountReservedAndCollidingAttemptsTogetherAgainstTheBound() {
        // reserved, collision, reserved, collision, collision with maxAttempts = 5
        when(generator.generate()).thenReturn("Health", "Coll1de", "Admin", "Coll1de", "Coll1de");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(codeCollision());

        assertThatThrownBy(() -> service.create(generated(URL))).isInstanceOf(ShortCodeUnavailableException.class);

        verify(generator, times(5)).generate();
        verify(repository, times(3)).saveAndFlush(any(ShortUrl.class));
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void shouldNotRetryAViolationOfAnotherConstraint() {
        DataIntegrityViolationException check = violation("23514", "ck_short_url_original_url_length");
        when(generator.generate()).thenReturn("Abc1234");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(check);

        assertThatThrownBy(() -> service.create(generated(URL))).isSameAs(check);

        verify(generator, times(1)).generate();
        verify(repository, times(1)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldNotRetryAUniqueViolationOnAnotherConstraint() {
        DataIntegrityViolationException other = violation("23505", "uk_something_else");
        when(generator.generate()).thenReturn("Abc1234");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(other);

        assertThatThrownBy(() -> service.create(generated(URL))).isSameAs(other);

        verify(generator, times(1)).generate();
    }

    @Test
    void shouldNotRetryANonDatabaseFailure() {
        IllegalStateException boom = new IllegalStateException("boom");
        when(generator.generate()).thenReturn("Abc1234");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(boom);

        assertThatThrownBy(() -> service.create(generated(URL))).isSameAs(boom);

        verify(generator, times(1)).generate();
    }

    // ---- D5, D11, D45, D47

    @Test
    void shouldCreateANewRowForTheSameUrlSubmittedTwice() {
        when(generator.generate()).thenReturn("Abc1234", "Xyz9876");

        ShortUrlView first = service.create(generated(URL));
        ShortUrlView second = service.create(generated(URL));

        assertThat(first.shortCode()).isNotEqualTo(second.shortCode());
        verify(repository, times(2)).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void shouldStoreTheIdenticalOriginalUrlStringWithoutNormalising() {
        String url = "HTTPS://Example.COM/a?b=1#f";
        when(generator.generate()).thenReturn("Abc1234");

        ShortUrlView view = service.create(generated(url));

        assertThat(savedEntities().getFirst().getOriginalUrl()).isEqualTo(url);
        assertThat(view.originalUrl()).isEqualTo(url);
    }

    @Test
    void shouldSetCreatedAtFromTheClockTruncatedToMicroseconds() {
        when(generator.generate()).thenReturn("Abc1234");

        ShortUrlView view = service.create(generated(URL));

        assertThat(view.createdAt()).isEqualTo(NOW_MICROS);
        assertThat(savedEntities().getFirst().getCreatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void shouldKeepTheArrivalTimeAcrossRetries() {
        when(generator.generate()).thenReturn("Coll1de", "Abc1234");
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenThrow(codeCollision())
                .thenAnswer(inv -> inv.getArgument(0));

        service.create(generated(URL));

        assertThat(savedEntities()).extracting(ShortUrl::getCreatedAt).containsOnly(NOW_MICROS);
    }

    // ---- logging (D64 and the "never log full URLs" rule)

    @Test
    void shouldLogTheCodeAndHostButNotThePathOrQueryOfTheUrl() {
        when(generator.generate()).thenReturn("Abc1234");

        service.create(generated("https://example.com/secret-path-marker?token=secret-query-marker"));

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        // Positive capture first, so the absence checks below cannot pass vacuously.
        assertThat(messages).anySatisfy(m -> assertThat(m)
                .contains("Short URL created").contains("Abc1234").contains("example.com"));
        assertThat(String.join("\n", messages))
                .doesNotContain("secret-path-marker")
                .doesNotContain("secret-query-marker")
                .doesNotContain("https://")
                .doesNotContain("alice");
    }

    @Test
    void shouldLogCollisionsAndExhaustionWithoutTheUrl() {
        when(generator.generate()).thenReturn("Coll1de");
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(codeCollision());

        assertThatThrownBy(() -> service.create(generated("https://example.com/secret-path-marker")))
                .isInstanceOf(ShortCodeUnavailableException.class);

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("collision").contains("Coll1de"));
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("exhausted");
        });
        assertThat(String.join("\n", messages)).doesNotContain("secret-path-marker").doesNotContain("alice");
    }

    @Test
    void shouldLogTheAliasConflictWithTheCodeOnly() {
        when(repository.saveAndFlush(any(ShortUrl.class))).thenThrow(codeCollision());

        assertThatThrownBy(() -> service.create(
                new CreateShortUrlCommand("https://example.com/secret-path-marker", "promo2026", "alice")))
                .isInstanceOf(AliasAlreadyExistsException.class);

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("Alias conflict").contains("promo2026"));
        assertThat(String.join("\n", messages)).doesNotContain("secret-path-marker").doesNotContain("alice");
    }

    @Test
    void shouldLogValidationRejectionsWithTheErrorCodeOnly() {
        assertThatThrownBy(() -> service.create(new CreateShortUrlCommand(URL, "bad alias marker", "alice")))
                .isInstanceOf(InvalidAliasException.class);
        assertThatThrownBy(() -> service.create(generated("ftp://example.com/secret-path-marker")))
                .isInstanceOf(InvalidUrlException.class);

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("INVALID_ALIAS"));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("INVALID_URL"));
        assertThat(String.join("\n", messages)).doesNotContain("bad alias marker")
                .doesNotContain("secret-path-marker");
    }

    // ---- get: ownership, check order, D72 (AC1-AC5, D4, D6, D13, D48)

    private static final Caller ALICE = new Caller("alice", false);
    private static final Caller BOB = new Caller("bob", false);
    private static final Caller ADMIN = new Caller("admin", true);

    private ShortUrl stored(String code, String owner, ShortUrlStatus status) {
        // Created at an earlier instant than the fake clock's, so "updatedAt unchanged" can fail for ACTIVE rows too.
        ShortUrl url = ShortUrl.create(code, URL, false, owner, EARLIER);
        if (status == ShortUrlStatus.DEACTIVATED) {
            url.deactivate(EARLIER);
        } else if (status == ShortUrlStatus.DELETED) {
            url.softDelete(FIRST_ADMIN, EARLIER);
        }
        when(repository.findByShortCode(code)).thenReturn(Optional.of(url));
        return url;
    }

    private void assertNotVisible(String code, Caller caller) {
        assertThatThrownBy(() -> service.get(code, caller)).isInstanceOf(ShortUrlNotFoundException.class);
        verify(repository, times(1)).findByShortCode(code);
    }

    @ParameterizedTest
    @EnumSource(value = ShortUrlStatus.class, names = {"ACTIVE", "DEACTIVATED"})
    void shouldReturnTheViewToTheOwnerForActiveAndDeactivatedLinks(ShortUrlStatus status) {
        stored("Abc1234", "alice", status);

        ShortUrlView view = service.get("Abc1234", ALICE);

        assertThat(view.shortCode()).isEqualTo("Abc1234");
        assertThat(view.status()).isEqualTo(status);
        assertThat(view.originalUrl()).isEqualTo(URL);
        assertThat(view.createdAt()).isEqualTo(EARLIER);
        assertThat(view.clickCount()).isZero();
        assertThat(view.lastAccessedAt()).isNull();
        verify(repository, times(1)).findByShortCode("Abc1234");
    }

    @ParameterizedTest
    @EnumSource(value = ShortUrlStatus.class, names = {"ACTIVE", "DEACTIVATED"})
    void shouldReturnAnotherUsersLinkToAnAdminForActiveAndDeactivatedLinks(ShortUrlStatus status) {
        stored("Abc1234", "bob", status);

        ShortUrlView view = service.get("Abc1234", ADMIN);

        assertThat(view.shortCode()).isEqualTo("Abc1234");
        assertThat(view.status()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = ShortUrlStatus.class, names = {"ACTIVE", "DEACTIVATED"})
    void shouldThrowNotFoundForANonOwnerUserOnActiveAndDeactivatedLinks(ShortUrlStatus status) {
        stored("Abc1234", "alice", status);

        assertNotVisible("Abc1234", BOB);
    }

    @Test
    void shouldThrowNotFoundForAnUnknownCode() {
        when(repository.findByShortCode("Nope123")).thenReturn(Optional.empty());

        assertNotVisible("Nope123", ALICE);
    }

    @Test
    void shouldThrowNotFoundForAnUnknownCodeWhenTheCallerIsAdmin() {
        when(repository.findByShortCode("Nope123")).thenReturn(Optional.empty());

        assertNotVisible("Nope123", ADMIN);
    }

    @Test
    void shouldThrowNotFoundForADeletedLinkForTheOwnerANonOwnerAndAdmin() {
        stored("Abc1234", "alice", ShortUrlStatus.DELETED);

        assertThatThrownBy(() -> service.get("Abc1234", ALICE)).isInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.get("Abc1234", BOB)).isInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.get("Abc1234", ADMIN)).isInstanceOf(ShortUrlNotFoundException.class);
        verify(repository, times(3)).findByShortCode("Abc1234");
    }

    @Test
    void shouldTreatTheOwnerComparisonAsExactAndCaseSensitive() {
        stored("Abc1234", "Alice", ShortUrlStatus.ACTIVE);

        assertNotVisible("Abc1234", ALICE);
    }

    @Test
    void shouldReturnAnExistingCodeThatIsNowAReservedWordBecauseOnlyTheFormatIsChecked() {
        stored("Health", "alice", ShortUrlStatus.ACTIVE);

        assertThat(service.get("Health", ALICE).shortCode()).isEqualTo("Health");
    }

    @Test
    void shouldLookUpTheCodeExactlyAsGivenWithoutFoldingCase() {
        when(repository.findByShortCode("AbC123")).thenReturn(Optional.empty());

        assertNotVisible("AbC123", ALICE);
        verify(repository, never()).findByShortCode("abc123");
        verify(repository, never()).findByShortCode("ABC123");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ab", "abcdefghijklmnopqrstuvwxyzABCDEFG", "a-b", "a_b", "abc ", " abc", "abcé",
            "ＡＢＣ", "abc%20", "abc.json", "abc\n"})
    void shouldThrowNotFoundWithoutAnyRepositoryCallForAMalformedCode(String code) {
        assertThatThrownBy(() -> service.get(code, ALICE)).isInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.get(code, ADMIN)).isInstanceOf(ShortUrlNotFoundException.class);

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 32})
    void shouldQueryTheRepositoryAtTheLengthBoundaries(int length) {
        String code = "a".repeat(length);
        when(repository.findByShortCode(code)).thenReturn(Optional.empty());

        assertNotVisible(code, ALICE);
    }

    @Test
    void shouldThrowIndistinguishableExceptionsForAllFourCauses() {
        stored("Owned12", "alice", ShortUrlStatus.ACTIVE);
        stored("Del1234", "alice", ShortUrlStatus.DELETED);
        when(repository.findByShortCode("Nope123")).thenReturn(Optional.empty());

        List<Throwable> thrown = List.of(
                catchThrowable(() -> service.get("a-b", ALICE)),
                catchThrowable(() -> service.get("Nope123", ALICE)),
                catchThrowable(() -> service.get("Del1234", ADMIN)),
                catchThrowable(() -> service.get("Owned12", BOB)));

        assertThat(thrown).allSatisfy(t -> assertThat(t).isExactlyInstanceOf(ShortUrlNotFoundException.class));
        assertThat(thrown).extracting(Throwable::getMessage).containsOnly("Short URL not found");
        assertThat(thrown).extracting(Throwable::getCause).containsOnlyNulls();
    }

    @Test
    void shouldRunGetInAReadOnlyRequiredTransactionAndCreateInARequiresNewWriteTransaction() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        service.get("Abc1234", ALICE);

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(1)).getTransaction(definitions.capture());
        assertThat(definitions.getValue().isReadOnly()).isTrue();
        assertThat(definitions.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        verify(transactionManager, times(1)).commit(transactionStatus);

        when(generator.generate()).thenReturn("Xyz9876");
        service.create(generated(URL));

        ArgumentCaptor<TransactionDefinition> all = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(all.capture());
        TransactionDefinition create = all.getAllValues().get(1);
        assertThat(create.isReadOnly()).isFalse();
        assertThat(create.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void shouldLogTheCodeAndReasonButNeverTheUsernameOnNotOwner() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        assertNotVisible("Abc1234", BOB);

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        // Positive capture first, so the absence check below cannot pass vacuously.
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("Abc1234").contains("NOT_OWNER"));
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.DEBUG));
        assertThat(String.join("\n", messages)).doesNotContain("bob").doesNotContain("alice")
                .doesNotContain("example.com");
    }

    @Test
    void shouldLogDeletedAndNotFoundReasonsWithTheCode() {
        stored("Del1234", "alice", ShortUrlStatus.DELETED);
        when(repository.findByShortCode("Nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("Del1234", ADMIN)).isInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.get("Nope123", ALICE)).isInstanceOf(ShortUrlNotFoundException.class);

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("Del1234").contains("DELETED"));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("Nope123").contains("NOT_FOUND"));
        assertThat(String.join("\n", messages)).doesNotContain("admin").doesNotContain("alice");
    }

    @Test
    void shouldNotLogTheSubmittedValueOfAMalformedCode() {
        assertThatThrownBy(() -> service.get("bad-code-marker!", ALICE)).isInstanceOf(ShortUrlNotFoundException.class);

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("MALFORMED"));
        assertThat(String.join("\n", messages)).doesNotContain("bad-code-marker").doesNotContain("alice");
    }

    @Test
    void shouldNotLogAnythingOnASuccessfulGet() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        service.get("Abc1234", ALICE);

        assertThat(logs.list).isEmpty();
    }

    // ---- US-009: update with active only (AC1-AC4, AC7-AC11, D26, D35)

    private static String joined(ListAppender<ILoggingEvent> appender) {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @ParameterizedTest
    @CsvSource({"alice,false", "admin,true"})
    void shouldDeactivateAnActiveLinkForItsOwnerOrAnAdminAndFlushExactlyOnce(String username, boolean admin) {
        ShortUrl url = stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        ShortUrlView view = service.update("Abc1234", UpdateShortUrlCommand.active(false), new Caller(username, admin));

        assertThat(view.status()).isEqualTo(ShortUrlStatus.DEACTIVATED);
        assertThat(view.shortCode()).isEqualTo("Abc1234");
        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DEACTIVATED);
        assertThat(url.getUpdatedAt()).isEqualTo(NOW_MICROS);
        verify(repository, times(1)).flush();
        verify(transactionManager, times(1)).commit(transactionStatus);
    }

    @ParameterizedTest
    @CsvSource({"alice,false", "admin,true"})
    void shouldReactivateADeactivatedLinkForItsOwnerOrAnAdmin(String username, boolean admin) {
        ShortUrl url = stored("Abc1234", "alice", ShortUrlStatus.DEACTIVATED);

        ShortUrlView view = service.update("Abc1234", UpdateShortUrlCommand.active(true), new Caller(username, admin));

        assertThat(view.status()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.ACTIVE);
        assertThat(url.getUpdatedAt()).isEqualTo(NOW_MICROS);
        verify(repository, times(1)).flush();
        verify(transactionManager, times(1)).commit(transactionStatus);
    }

    @Test
    void shouldLetAnAdminChangeAnotherUsersLinkInBothDirections() {
        stored("Abc1234", "bob", ShortUrlStatus.ACTIVE);

        assertThat(service.update("Abc1234", UpdateShortUrlCommand.active(false), ADMIN).status())
                .isEqualTo(ShortUrlStatus.DEACTIVATED);
        assertThat(service.update("Abc1234", UpdateShortUrlCommand.active(true), ADMIN).status())
                .isEqualTo(ShortUrlStatus.ACTIVE);
        verify(repository, times(2)).flush();
    }

    @Test
    void shouldReturnTheClickDataReadInTheTransactionUnchanged() {
        ShortUrl url = stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        ReflectionTestUtils.setField(url, "clickCount", 7L);
        ReflectionTestUtils.setField(url, "lastAccessedAt", NOW_MICROS);

        ShortUrlView view = service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE);

        assertThat(view.clickCount()).isEqualTo(7L);
        assertThat(view.lastAccessedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void shouldRunTheStepsInOrderGetTransactionFindFlushCommit() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE);

        InOrder order = inOrder(transactionManager, repository);
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).findByShortCode("Abc1234");
        order.verify(repository).flush();
        order.verify(transactionManager).commit(transactionStatus);
        verify(transactionManager, never()).rollback(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRunUpdateAndDeleteInARequiredReadWriteTransaction(boolean deleteInstead) {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        if (deleteInstead) {
            service.delete("Abc1234", ADMIN);
        } else {
            service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE);
        }

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(1)).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(definition.getValue().isReadOnly()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"DEACTIVATED,false", "ACTIVE,true"})
    void shouldRollBackWithoutFlushingWhenTheTransitionIsRedundant(ShortUrlStatus current, boolean active) {
        ShortUrl url = stored("Abc1234", "alice", current);
        Instant updatedBefore = url.getUpdatedAt();

        Class<? extends Exception> expected = active
                ? ShortUrlAlreadyActiveException.class : ShortUrlAlreadyDeactivatedException.class;
        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(active), ALICE))
                .isExactlyInstanceOf(expected);
        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(active), ADMIN))
                .isExactlyInstanceOf(expected);

        assertThat(url.getStatus()).isEqualTo(current);
        assertThat(url.getUpdatedAt()).isEqualTo(updatedBefore);
        verify(repository, never()).flush();
        verify(transactionManager, times(2)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        // Positive control: the opposite transition on the same kind of row does write.
        ShortUrl other = stored("Xyz9876", "alice", current);
        service.update("Xyz9876", UpdateShortUrlCommand.active(!active), ALICE);
        assertThat(other.getStatus()).isNotEqualTo(current);
        verify(repository, times(1)).flush();
    }

    @ParameterizedTest
    @EnumSource(value = ShortUrlStatus.class, names = {"ACTIVE", "DEACTIVATED"})
    void shouldThrowNotFoundAndNeverFlushForANonOwnerUserInBothDirections(ShortUrlStatus current) {
        ShortUrl url = stored("Abc1234", "alice", current);

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(true), BOB))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), BOB))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);

        assertThat(url.getStatus()).isEqualTo(current);
        verify(repository, never()).flush();
        verify(transactionManager, never()).commit(any());
        verify(transactionManager, times(2)).rollback(transactionStatus);
    }

    @Test
    void shouldThrowNotFoundForADeletedLinkForOwnerNonOwnerAndAdminInBothDirections() {
        ShortUrl url = stored("Abc1234", "alice", ShortUrlStatus.DELETED);

        for (Caller caller : List.of(ALICE, BOB, ADMIN)) {
            for (boolean active : new boolean[] {true, false}) {
                assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(active), caller))
                        .isExactlyInstanceOf(ShortUrlNotFoundException.class);
            }
        }

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        verify(repository, times(6)).findByShortCode("Abc1234");
        verify(repository, never()).flush();
    }

    @Test
    void shouldThrowNotFoundForAnUnknownCodeInBothDirections() {
        when(repository.findByShortCode("Nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("Nope123", UpdateShortUrlCommand.active(false), ALICE))
                .isInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.update("Nope123", UpdateShortUrlCommand.active(true), ADMIN))
                .isInstanceOf(ShortUrlNotFoundException.class);

        verify(repository, never()).flush();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ab", "abcdefghijklmnopqrstuvwxyzABCDEFG", "a-b", "abc ", "abcé", "abc\n"})
    void shouldThrowNotFoundWithoutAnyRepositoryCallForAMalformedCodeOnUpdate(String code) {
        assertThatThrownBy(() -> service.update(code, UpdateShortUrlCommand.active(false), ALICE))
                .isInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.update(code, UpdateShortUrlCommand.active(true), ADMIN))
                .isInstanceOf(ShortUrlNotFoundException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void shouldTakeTheTimestampFromTheClockExactlyOncePerRequest() {
        Clock counting = mock(Clock.class);
        when(counting.instant()).thenReturn(NOW);
        ShortUrlService counted = new ShortUrlService(repository, clickEvents, generator,
                new UrlValidator("https://short.example"), new AliasPolicy(List.of()), EXPIRATION, counting,
                new ShortCodeProperties(7, 5), transactionManager);
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        counted.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE);

        verify(counting, times(1)).instant();
    }

    @Test
    void shouldTakeTheTimestampFromTheClockExactlyOncePerDeleteRequest() {
        Clock counting = mock(Clock.class);
        when(counting.instant()).thenReturn(NOW);
        ShortUrlService counted = new ShortUrlService(repository, clickEvents, generator,
                new UrlValidator("https://short.example"), new AliasPolicy(List.of()), EXPIRATION, counting,
                new ShortCodeProperties(7, 5), transactionManager);
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        counted.delete("Abc1234", ADMIN);

        verify(counting, times(1)).instant();
    }

    // ---- US-009: flush and catch placement (AC11, D35, D87)

    @Test
    void shouldTranslateAnObjectOptimisticLockingFailureFromFlushAndRollBackWithoutCommit() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(ShortUrl.class, 1L);
        doThrow(conflict).when(repository).flush();

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isExactlyInstanceOf(ShortUrlConcurrentModificationException.class)
                .hasCause(conflict)
                .hasMessage("Short URL was modified concurrently");

        verify(repository, times(1)).flush();
        verify(transactionManager, times(1)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void shouldTranslateAJpaOptimisticLockingFailureFromFlushBecauseTheBaseClassIsCaught() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        JpaOptimisticLockingFailureException conflict =
                new JpaOptimisticLockingFailureException(new OptimisticLockException());
        doThrow(conflict).when(repository).flush();

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isExactlyInstanceOf(ShortUrlConcurrentModificationException.class)
                .hasCause(conflict);
    }

    @Test
    void shouldTranslateTheBaseOptimisticLockingFailureItself() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        OptimisticLockingFailureException conflict = new OptimisticLockingFailureException("stale");
        doThrow(conflict).when(repository).flush();

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isExactlyInstanceOf(ShortUrlConcurrentModificationException.class)
                .hasCause(conflict);
    }

    @Test
    void shouldTranslateAnOptimisticLockingFailureThrownByCommitBecauseTheCatchIsOutsideTheTemplate() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(ShortUrl.class, 1L);
        doThrow(conflict).when(transactionManager).commit(transactionStatus);

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isExactlyInstanceOf(ShortUrlConcurrentModificationException.class)
                .hasCause(conflict);

        verify(repository, times(1)).flush();
        verify(transactionManager, times(1)).commit(transactionStatus);
    }

    @Test
    void shouldNotTranslateADataAccessFailureFromFlushBecauseOnlyOptimisticLockingIsCaught() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("db down");
        doThrow(outage).when(repository).flush();

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isSameAs(outage);
    }

    @Test
    void shouldNotTranslateAConstraintViolationFromFlushToAConflict() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        DataIntegrityViolationException check = violation("23514", "ck_short_url_deleted_consistency");
        doThrow(check).when(repository).flush();

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE)).isSameAs(check);
    }

    @Test
    void shouldTranslateAnOptimisticLockingFailureFromFlushOnDeleteAndNotADataAccessFailure() {
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(ShortUrl.class, 1L);
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        doThrow(conflict).when(repository).flush();

        assertThatThrownBy(() -> service.delete("Abc1234", ADMIN))
                .isExactlyInstanceOf(ShortUrlConcurrentModificationException.class)
                .hasCause(conflict);
        verify(transactionManager, never()).commit(any());

        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("db down");
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);      // a fresh entity: the first attempt changed the old one
        doThrow(outage).when(repository).flush();
        assertThatThrownBy(() -> service.delete("Abc1234", ADMIN)).isSameAs(outage);
    }

    @Test
    void shouldTranslateAnOptimisticLockingFailureThrownByCommitOnDelete() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(ShortUrl.class, 1L);
        doThrow(conflict).when(transactionManager).commit(transactionStatus);

        assertThatThrownBy(() -> service.delete("Abc1234", ADMIN))
                .isExactlyInstanceOf(ShortUrlConcurrentModificationException.class)
                .hasCause(conflict);
    }

    // ---- US-009: delete (AC5, AC6, AC7, AC8, D1, D3, D36, D46, D51)

    @ParameterizedTest
    @EnumSource(value = ShortUrlStatus.class, names = {"ACTIVE", "DEACTIVATED"})
    void shouldSoftDeleteAnActiveOrDeactivatedLinkWithTheAdminUsernameAndClockTimeAndFlushOnce(ShortUrlStatus current) {
        ShortUrl url = stored("Abc1234", "alice", current);

        service.delete("Abc1234", ADMIN);

        assertThat(url.getStatus()).isEqualTo(ShortUrlStatus.DELETED);
        assertThat(url.getDeletedBy()).isEqualTo("admin");
        assertThat(url.getDeletedAt()).isEqualTo(NOW_MICROS);
        assertThat(url.getUpdatedAt()).isEqualTo(NOW_MICROS);
        assertThat(url.getCreatedBy()).isEqualTo("alice");
        verify(repository, times(1)).flush();
        verify(transactionManager, times(1)).commit(transactionStatus);
    }

    @Test
    void shouldStoreExactlyTheCallersUsernameAsDeletedByWhateverItIs() {
        ShortUrl url = stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        service.delete("Abc1234", new Caller("root-admin", true));

        assertThat(url.getDeletedBy()).isEqualTo("root-admin");
    }

    @Test
    void shouldRunDeleteStepsInOrderGetTransactionFindFlushCommit() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        service.delete("Abc1234", ADMIN);

        InOrder order = inOrder(transactionManager, repository);
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).findByShortCode("Abc1234");
        order.verify(repository).flush();
        order.verify(transactionManager).commit(transactionStatus);
    }

    @ParameterizedTest
    @CsvSource({"alice,false", "bob,false", "someone,false"})
    void shouldThrowIllegalStateForANonAdminBeforeAnyLookupOrTransaction(String username, boolean admin) {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        clearInvocations(repository, transactionManager);

        assertThatThrownBy(() -> service.delete("Abc1234", new Caller(username, admin)))
                .isExactlyInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.delete("no-such-code!", new Caller(username, admin)))
                .isExactlyInstanceOf(IllegalStateException.class);

        verify(repository, never()).findByShortCode(any());
        verify(repository, never()).flush();
        verifyNoInteractions(transactionManager);
    }

    @Test
    void shouldThrowNotFoundForADeletedUnknownAndMalformedCodeWhenAdminDeletes() {
        ShortUrl deleted = stored("Del1234", "alice", ShortUrlStatus.DELETED);
        when(repository.findByShortCode("Nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("Del1234", ADMIN)).isExactlyInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.delete("Nope123", ADMIN)).isExactlyInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.delete("a-b", ADMIN)).isExactlyInstanceOf(ShortUrlNotFoundException.class);

        assertThat(deleted.getDeletedBy()).isEqualTo(FIRST_ADMIN);       // first delete's values untouched
        assertThat(deleted.getDeletedAt()).isEqualTo(EARLIER);
        assertThat(deleted.getUpdatedAt()).isEqualTo(EARLIER);
        verify(repository, times(1)).findByShortCode("Del1234");
        verify(repository, times(1)).findByShortCode("Nope123");
        verify(repository, never()).findByShortCode("a-b");
        verify(repository, never()).flush();
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void shouldNotAllowASecondDeleteToOverwriteTheFirstDeletesAuditValues() {
        ShortUrl url = stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        service.delete("Abc1234", new Caller("admin", true));

        assertThatThrownBy(() -> service.delete("Abc1234", new Caller("other-admin", true)))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);

        assertThat(url.getDeletedBy()).isEqualTo("admin");
        verify(repository, times(1)).flush();
    }

    // ---- US-009: logging (D52, D64)

    @Test
    void shouldLogTheSuccessLinesWithTheCodeButNeverTheUsernameOrTheUrl() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        stored("Del1234", "alice", ShortUrlStatus.ACTIVE);

        service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE);
        service.update("Abc1234", UpdateShortUrlCommand.active(true), ALICE);
        service.delete("Del1234", ADMIN);

        List<String> messages = logs.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
        // Positive capture first, so the absence checks below cannot pass vacuously.
        assertThat(messages).containsExactly(
                "Short URL deactivated: code=Abc1234",
                "Short URL reactivated: code=Abc1234",
                "Short URL deleted: code=Del1234");
        assertThat(joined(logs)).doesNotContain("alice").doesNotContain("admin")
                .doesNotContain("example.com").doesNotContain("https://");
    }

    @Test
    void shouldLogAConflictAtInfoWithCodeAndActionOnly() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        doThrow(new ObjectOptimisticLockingFailureException(ShortUrl.class, 1L)).when(repository).flush();

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isInstanceOf(ShortUrlConcurrentModificationException.class);
        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(true), ALICE))
                .isInstanceOf(ShortUrlConcurrentModificationException.class);
        assertThatThrownBy(() -> service.delete("Abc1234", ADMIN))
                .isInstanceOf(ShortUrlConcurrentModificationException.class);

        List<ILoggingEvent> conflicts = logs.list.stream()
                .filter(e -> e.getFormattedMessage().contains("changed concurrently")).toList();
        assertThat(conflicts).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Short URL changed concurrently: code=Abc1234 action=DEACTIVATE",
                "Short URL changed concurrently: code=Abc1234 action=REACTIVATE",
                "Short URL changed concurrently: code=Abc1234 action=DELETE");
        assertThat(conflicts).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.WARN)).isTrue());
        assertThat(joined(logs)).doesNotContain("alice").doesNotContain("admin").doesNotContain("https://");
    }

    @Test
    void shouldNotLogASuccessLineWhenTheCommitFails() {
        stored("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        stored("Del1234", "alice", ShortUrlStatus.ACTIVE);
        doThrow(new ObjectOptimisticLockingFailureException(ShortUrl.class, 1L))
                .when(transactionManager).commit(transactionStatus);

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isInstanceOf(ShortUrlConcurrentModificationException.class);
        assertThatThrownBy(() -> service.delete("Del1234", ADMIN))
                .isInstanceOf(ShortUrlConcurrentModificationException.class);

        // Positive capture: the conflict lines were logged, so the absence check is about the success lines.
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("changed concurrently"));
        assertThat(joined(logs)).doesNotContain("deactivated").doesNotContain("reactivated")
                .doesNotContain("deleted");
    }

    @Test
    void shouldLogNothingAtInfoForARedundantTransition() {
        stored("Abc1234", "alice", ShortUrlStatus.DEACTIVATED);

        assertThatThrownBy(() -> service.update("Abc1234", UpdateShortUrlCommand.active(false), ALICE))
                .isInstanceOf(ShortUrlAlreadyDeactivatedException.class);

        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
    }

    // ---- US-011: stats (AC4, AC5, AC6, AC15, AC16; D95, D102, D104, D105)

    private static final long STATS_ID = 42L;

    private ShortUrl storedWithClicks(String code, String owner, ShortUrlStatus status) {
        ShortUrl url = stored(code, owner, status);
        ReflectionTestUtils.setField(url, "id", STATS_ID);
        ReflectionTestUtils.setField(url, "clickCount", 7L);
        ReflectionTestUtils.setField(url, "lastAccessedAt", NOW_MICROS);
        return url;
    }

    @Test
    void shouldBuildStatsFromTheEntityAndTheDensifiedRows() {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class)))
                .thenReturn(List.of(new ClickEventRepository.DayCount(1, 2),
                        new ClickEventRepository.DayCount(3, 4)));

        ShortUrlStats stats = service.stats("Abc1234", "America/New_York", "2026-03-07", "2026-03-09", ALICE);

        assertThat(stats.shortCode()).isEqualTo("Abc1234");
        assertThat(stats.zoneId()).isEqualTo("America/New_York");
        assertThat(stats.from()).isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(stats.to()).isEqualTo(LocalDate.of(2026, 3, 9));
        // totalClicks is the entity's counter (7), clicksInRange the sum of the rows (6): deliberately different.
        assertThat(stats.totalClicks()).isEqualTo(7L);
        assertThat(stats.clicksInRange()).isEqualTo(6L);
        assertThat(stats.lastAccessedAt()).isEqualTo(NOW_MICROS);
        assertThat(stats.daily()).containsExactly(new DailyClicks(LocalDate.of(2026, 3, 7), 2),
                new DailyClicks(LocalDate.of(2026, 3, 8), 0), new DailyClicks(LocalDate.of(2026, 3, 9), 4));
        ArgumentCaptor<List<Instant>> starts = ArgumentCaptor.captor();
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(clickEvents).countClicksPerDay(eq(STATS_ID), starts.capture(), end.capture());
        assertThat(starts.getValue()).containsExactly(Instant.parse("2026-03-07T05:00:00Z"),
                Instant.parse("2026-03-08T05:00:00Z"), Instant.parse("2026-03-09T04:00:00Z"));
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-03-10T04:00:00Z"));
    }

    @Test
    void shouldTakeTotalClicksFromTheCounterEvenWhenTheRowsDisagree() {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        ShortUrlStats stats = service.stats("Abc1234", "UTC", "2026-03-01", "2026-03-01", ALICE);

        assertThat(stats.totalClicks()).isEqualTo(7L);
        assertThat(stats.clicksInRange()).isZero();
        assertThat(stats.daily()).containsExactly(new DailyClicks(LocalDate.of(2026, 3, 1), 0));
    }

    @Test
    void shouldReturnNullLastAccessedAtBeforeTheFirstClick() {
        ShortUrl url = storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        ReflectionTestUtils.setField(url, "lastAccessedAt", null);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        assertThat(service.stats("Abc1234", null, null, null, ALICE).lastAccessedAt()).isNull();
    }

    @Test
    void shouldApplyTheDefaultsFromTheInjectedClockInTheRequestedZone() {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        ShortUrlStats utc = service.stats("Abc1234", null, null, null, ALICE);
        ShortUrlStats auckland = service.stats("Abc1234", "Pacific/Auckland", null, null, ALICE);

        // NOW is 2026-09-29T14:03Z: still the 29th in UTC, already the 30th in Auckland (+13).
        assertThat(utc.zoneId()).isEqualTo("UTC");
        assertThat(utc.to()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(utc.from()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(utc.daily()).hasSize(30);
        assertThat(auckland.to()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void shouldDefaultToUtcDaysEvenWhenTheInjectedClockHasAnotherZone() {
        Clock tokyo = Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Tokyo"));
        ShortUrlService inTokyo = new ShortUrlService(repository, clickEvents, generator,
                new UrlValidator("https://short.example"), new AliasPolicy(List.of()), EXPIRATION, tokyo,
                new ShortCodeProperties(7, 5), transactionManager);
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        // 20:00Z is already 2026-03-11 in Tokyo; UTC is the default zone, whatever zone the clock carries.
        assertThat(inTokyo.stats("Abc1234", null, null, null, ALICE).to()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void shouldRunStatsInAReadOnlyRepeatableReadTransactionThatCommits() {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        service.stats("Abc1234", null, null, null, ALICE);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(1)).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        verify(transactionManager, times(1)).commit(transactionStatus);
        // The other templates are unchanged: get stays on the default isolation.
        service.get("Abc1234", ALICE);
        ArgumentCaptor<TransactionDefinition> all = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(all.capture());
        assertThat(all.getAllValues().get(1).getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
    }

    @Test
    void shouldReadTheClockExactlyOncePerStatsRequest() {
        Clock counting = mock(Clock.class);
        when(counting.instant()).thenReturn(NOW);
        ShortUrlService counted = new ShortUrlService(repository, clickEvents, generator,
                new UrlValidator("https://short.example"), new AliasPolicy(List.of()), EXPIRATION, counting,
                new ShortCodeProperties(7, 5), transactionManager);
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        counted.stats("Abc1234", null, null, null, ALICE);

        verify(counting, times(1)).instant();
    }

    @ParameterizedTest
    @CsvSource(value = {"+05:00,,", "UTC,2026-02-30,", "UTC,,bad", "UTC,2026-03-09,2026-03-07",
            "UTC,2025-01-01,2026-01-02", "'',,"})
    void shouldRejectBadParametersBeforeAnyTransactionOrRepositoryInteraction(String timezone, String from,
            String to) {
        assertThatThrownBy(() -> service.stats("Abc1234", timezone, from, to, ALICE))
                .isExactlyInstanceOf(InvalidStatsQueryException.class);

        verifyNoInteractions(repository, clickEvents, transactionManager);
        // Positive control: the same code with valid parameters does reach the repository.
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());
        service.stats("Abc1234", "UTC", "2026-03-01", "2026-03-01", ALICE);
        verify(repository, times(1)).findByShortCode("Abc1234");
    }

    @Test
    void shouldValidateParametersBeforeVisibilityForACodeTheCallerCannotSee() {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);

        assertThatThrownBy(() -> service.stats("Abc1234", "+05:00", null, null, BOB))
                .isExactlyInstanceOf(InvalidStatsQueryException.class);
        assertThatThrownBy(() -> service.stats("a-b", "+05:00", null, null, BOB))
                .isExactlyInstanceOf(InvalidStatsQueryException.class);
        assertThatThrownBy(() -> service.stats("Abc1234", "UTC", null, null, BOB))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);

        verify(repository, times(1)).findByShortCode("Abc1234");
    }

    @Test
    void shouldLogOnlyTheParameterNamesWhenParametersAreRejected() {
        assertThatThrownBy(() -> service.stats("Abc1234", "secret-zone-marker", "bad-date-marker", null, ALICE))
                .isInstanceOf(InvalidStatsQueryException.class);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(joined(logs)).contains("VALIDATION_FAILED").contains("timezone").contains("from")
                .doesNotContain("marker").doesNotContain("Abc1234");
    }

    @Test
    void shouldGiveTheSameNotFoundForMalformedUnknownDeletedAndNotOwnedCodesAndNeverCountClicks() {
        storedWithClicks("Owned12", "alice", ShortUrlStatus.ACTIVE);
        storedWithClicks("Del1234", "alice", ShortUrlStatus.DELETED);
        when(repository.findByShortCode("Nope123")).thenReturn(Optional.empty());

        List<Throwable> thrown = List.of(
                catchThrowable(() -> service.stats("a-b", null, null, null, ALICE)),
                catchThrowable(() -> service.stats("Nope123", null, null, null, ALICE)),
                catchThrowable(() -> service.stats("Del1234", null, null, null, ADMIN)),
                catchThrowable(() -> service.stats("Del1234", null, null, null, ALICE)),
                catchThrowable(() -> service.stats("Owned12", null, null, null, BOB)));

        assertThat(thrown).allSatisfy(t -> assertThat(t).isExactlyInstanceOf(ShortUrlNotFoundException.class));
        assertThat(thrown).extracting(Throwable::getMessage).containsOnly("Short URL not found");
        verifyNoInteractions(clickEvents);
        // Positive control: the owner of the same link gets stats through the same service instance.
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());
        assertThat(service.stats("Owned12", null, null, null, ALICE).shortCode()).isEqualTo("Owned12");
    }

    @ParameterizedTest
    @CsvSource({"alice,false", "admin,true"})
    void shouldReturnStatsOfADeactivatedLinkToItsOwnerAndToAnAdminButNotToAnotherUser(String username,
            boolean admin) {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.DEACTIVATED);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        ShortUrlStats stats = service.stats("Abc1234", null, null, null, new Caller(username, admin));

        assertThat(stats.shortCode()).isEqualTo("Abc1234");
        assertThat(stats.totalClicks()).isEqualTo(7L);
        assertThatThrownBy(() -> service.stats("Abc1234", null, null, null, BOB))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void shouldLetAnAdminReadStatsOfAnotherUsersActiveLink() {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class))).thenReturn(List.of());

        assertThat(service.stats("Abc1234", null, null, null, ADMIN).shortCode()).isEqualTo("Abc1234");
    }

    @Test
    void shouldPropagateAnOutOfRangeBucketIndexAsAnIllegalStateException() {
        storedWithClicks("Abc1234", "alice", ShortUrlStatus.ACTIVE);
        when(clickEvents.countClicksPerDay(eq(STATS_ID), anyList(), any(Instant.class)))
                .thenReturn(List.of(new ClickEventRepository.DayCount(2, 1)));

        assertThatThrownBy(() -> service.stats("Abc1234", "UTC", "2026-03-01", "2026-03-01", ALICE))
                .isExactlyInstanceOf(IllegalStateException.class);
        verify(transactionManager, times(1)).rollback(transactionStatus);
    }

    // ---- transaction boundary guard

    @Test
    void shouldNotBeTransactionalAtClassOrMethodLevelSoEveryAttemptOwnsItsTransaction() {
        assertThat(ShortUrlService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(ShortUrlService.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        for (Method method : ShortUrlService.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Transactional.class)).as(method.getName()).isFalse();
            assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class))
                    .as(method.getName()).isFalse();
        }
        // Guards against the loop above passing vacuously.
        assertThat(Arrays.stream(ShortUrlService.class.getDeclaredMethods()).map(Method::getName))
                .contains("create");
    }
}
