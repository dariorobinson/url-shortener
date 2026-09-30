package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.schwab.urlshortener.config.ShortCodeProperties;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortCodeUnavailableException;
import com.schwab.urlshortener.shortcode.ShortCodeGenerator;
import com.schwab.urlshortener.validation.AliasPolicy;
import com.schwab.urlshortener.validation.UrlValidator;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create logic (AC2, AC3, AC4, AC5, AC6, AC7, AC11, AC15, D5, D29, D45, D47, D48, D60, D63, D64) against
 * mocks: no Spring context, no database.
 */
class ShortUrlServiceTest {

    private static final String URL = "https://example.com/page";
    private static final Instant NOW = Instant.parse("2026-09-29T14:03:12.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-09-29T14:03:12.123456Z");

    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
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
        return new ShortUrlService(repository, generator, new UrlValidator("https://short.example"),
                new AliasPolicy(List.of()), clock, new ShortCodeProperties(7, maxAttempts), transactionManager);
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
