package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redirect resolution (US-008 AC1 to AC4, AC6; D2, D48, D72, D77) against mocks: no Spring context, no database.
 */
class RedirectServiceTest {

    private static final String URL_MARKER = "https://secret-host.example/private?token=marker";
    private static final Instant AT = Instant.parse("2026-09-29T14:03:12.123456Z");

    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(RedirectService.class);
    private Level originalLevel;

    private RedirectService service;

    @BeforeEach
    void setUp() {
        service = new RedirectService(repository, transactionManager);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
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

    private ShortUrl stored(String code, String url, ShortUrlStatus status) {
        ShortUrl entity = ShortUrl.create(code, url, false, "alice", AT);
        if (status == ShortUrlStatus.DEACTIVATED) {
            entity.deactivate(AT);
        } else if (status == ShortUrlStatus.DELETED) {
            entity.softDelete("admin", AT);
        }
        when(repository.findByShortCode(code)).thenReturn(Optional.of(entity));
        return entity;
    }

    private String logged() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    // ---- AC1

    @ParameterizedTest
    @ValueSource(strings = {
            "https://Example.COM:8443/a/../b;p?q=a+b&c=%2f%2F#frag",
            "https://example.com/path%2Fwith%2fescapes",
            "https://example.com",
            "https://example.com/café?q=ü"})
    void shouldReturnTheStoredUrlUnchangedForAnActiveLink(String url) {
        stored("abc1234", url, ShortUrlStatus.ACTIVE);

        assertThat(service.resolve("abc1234")).isEqualTo(url);
    }

    @Test
    void shouldQueryTheRepositoryOnceWithTheExactCodeAndNoCaseFolding() {
        stored("MiXed12", "https://example.com/x", ShortUrlStatus.ACTIVE);

        service.resolve("MiXed12");

        verify(repository, times(1)).findByShortCode("MiXed12");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldRedirectAnExistingActiveCodeThatIsNowAReservedWord() {
        stored("Health", "https://example.com/h", ShortUrlStatus.ACTIVE);

        assertThat(service.resolve("Health")).isEqualTo("https://example.com/h");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 32})
    void shouldQueryTheRepositoryAtTheLengthBoundaries(int length) {
        String code = "a".repeat(length);
        stored(code, "https://example.com/b", ShortUrlStatus.ACTIVE);

        assertThat(service.resolve(code)).isEqualTo("https://example.com/b");
        verify(repository).findByShortCode(code);
    }

    // ---- AC2, AC3, AC4

    @Test
    void shouldThrowNotFoundForAnUnknownCode() {
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(ShortUrlNotFoundException.class);

        verify(transactionManager, times(1)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEACTIVATED", "DELETED"})
    void shouldThrowTheSameNotFoundForANonActiveLinkAsForAnUnknownCode(String status) {
        when(repository.findByShortCode("unk1234")).thenReturn(Optional.empty());
        Throwable unknown = catchThrowable(() -> service.resolve("unk1234"));
        stored("abc1234", URL_MARKER, ShortUrlStatus.valueOf(status));

        Throwable inactive = catchThrowable(() -> service.resolve("abc1234"));

        assertThat(inactive).isExactlyInstanceOf(ShortUrlNotFoundException.class);
        assertThat(unknown).isExactlyInstanceOf(ShortUrlNotFoundException.class);
        assertThat(inactive.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(inactive.getCause()).isNull();
    }

    // ---- AC6, D72, D77

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ab", "abc ", " abc", "a-b", "a_b", "abc.json", "favicon.ico", "abcé",
            "ＡＢＣ", "abc\n", "a b c"})
    void shouldThrowNotFoundForAMalformedCodeWithoutTouchingTheRepositoryOrTransactionManager(String code) {
        assertThatThrownBy(() -> service.resolve(code)).isExactlyInstanceOf(ShortUrlNotFoundException.class);

        verifyNoInteractions(repository, transactionManager);
    }

    @Test
    void shouldThrowNotFoundForA33CharacterCodeWithoutTouchingTheRepositoryOrTransactionManager() {
        assertThatThrownBy(() -> service.resolve("a".repeat(33))).isExactlyInstanceOf(ShortUrlNotFoundException.class);

        verifyNoInteractions(repository, transactionManager);
    }

    // ---- transaction

    @Test
    void shouldReadInAReadOnlyRequiredTransactionThatIsFinishedBeforeResolveReturns() {
        stored("abc1234", "https://example.com/x", ShortUrlStatus.ACTIVE);

        service.resolve("abc1234");

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(1)).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        verify(transactionManager, times(1)).commit(transactionStatus);
    }

    @Test
    void shouldNotBeTransactionalAtClassOrMethodLevel() {
        assertThat(RedirectService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(RedirectService.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        Method[] methods = RedirectService.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods).map(Method::getName)).contains("resolve");
        for (Method method : methods) {
            assertThat(method.isAnnotationPresent(Transactional.class)).as(method.getName()).isFalse();
            assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class))
                    .as(method.getName()).isFalse();
        }
    }

    // ---- logging (CLAUDE.md: never the URL, host or client text)

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "DEACTIVATED", "DELETED"})
    void shouldLogTheCodeAndReasonButNeverTheUrlOrHostForANonRedirectingLink(String reason) {
        if (reason.equals("NOT_FOUND")) {
            when(repository.findByShortCode("abc1234")).thenReturn(Optional.empty());
        } else {
            stored("abc1234", URL_MARKER, ShortUrlStatus.valueOf(reason));
        }

        assertThatThrownBy(() -> service.resolve("abc1234")).isInstanceOf(ShortUrlNotFoundException.class);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(logged()).contains("code=abc1234").contains("reason=" + reason);
        assertThat(logged()).doesNotContain("secret-host").doesNotContain("marker").doesNotContain("https://");
    }

    @Test
    void shouldLogTheMalformedReasonWithoutTheSubmittedValue() {
        assertThatThrownBy(() -> service.resolve("evil-value.marker")).isInstanceOf(ShortUrlNotFoundException.class);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(logged()).contains("reason=MALFORMED");
        assertThat(logged()).doesNotContain("evil-value").doesNotContain("marker");
    }

    @Test
    void shouldNotLogAnythingForASuccessfulResolution() {
        stored("abc1234", URL_MARKER, ShortUrlStatus.ACTIVE);

        service.resolve("abc1234");

        assertThat(logs.list).isEmpty();
    }
}
