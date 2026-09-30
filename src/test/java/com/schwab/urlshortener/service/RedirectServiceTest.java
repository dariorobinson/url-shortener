package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import com.schwab.urlshortener.analytics.ClickRecorder;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.validation.LocationEncoder;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
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
    /** The click time: distinct from every fixture timestamp, so a wrong source of time cannot pass. */
    private static final Instant CLICK_AT = Instant.parse("2026-09-30T08:15:30.987654Z");
    private static final long LINK_ID = 4711L;

    private final ShortUrlRepository repository = mock(ShortUrlRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final ClickRecorder clickRecorder = mock(ClickRecorder.class);
    private final Clock clock = Clock.fixed(CLICK_AT, ZoneOffset.UTC);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(RedirectService.class);
    private Level originalLevel;

    private RedirectService service;

    @BeforeEach
    void setUp() {
        service = new RedirectService(repository, transactionManager, clickRecorder, clock);
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
        ReflectionTestUtils.setField(entity, "id", LINK_ID);   // create() leaves the id null; lookup needs it
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
        assertThat(Arrays.stream(methods).map(Method::getName)).contains("resolve", "resolveAndRecordClick");
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

    // ---- US-010: click recording on GET (AC1, AC2, AC3; D9, D12, D91, D93)

    @Test
    void shouldRecordTheResolvedIdAndTheClockInstantOnlyAfterTheReadTransactionCommits() {
        stored("abc1234", "https://example.com/x", ShortUrlStatus.ACTIVE);

        String target = service.resolveAndRecordClick("abc1234");

        assertThat(target).isEqualTo("https://example.com/x");
        InOrder order = inOrder(transactionManager, clickRecorder);
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(clickRecorder).record(LINK_ID, CLICK_AT);
        verify(clickRecorder, times(1)).record(any(Long.class), any());
    }

    @Test
    void shouldNeverTouchTheRecorderOrTheClockWhenResolvingWithoutRecording() {
        Clock untouchedClock = mock(Clock.class);
        RedirectService headService = new RedirectService(repository, transactionManager, clickRecorder,
                untouchedClock);
        stored("abc1234", "https://example.com/x", ShortUrlStatus.ACTIVE);

        assertThat(headService.resolve("abc1234")).isEqualTo("https://example.com/x");

        verifyNoInteractions(clickRecorder, untouchedClock);
        // Positive control: the same link through the recording method does reach the recorder.
        service.resolveAndRecordClick("abc1234");
        verify(clickRecorder, times(1)).record(LINK_ID, CLICK_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "DEACTIVATED", "DELETED", "MALFORMED"})
    void shouldNotRecordAClickWhenTheLinkDoesNotRedirect(String kind) {
        String code = kind.equals("MALFORMED") ? "a-b" : "abc1234";
        switch (kind) {
            case "UNKNOWN" -> when(repository.findByShortCode(code)).thenReturn(Optional.empty());
            case "DEACTIVATED", "DELETED" -> stored(code, URL_MARKER, ShortUrlStatus.valueOf(kind));
            default -> { }
        }

        assertThatThrownBy(() -> service.resolveAndRecordClick(code))
                .isExactlyInstanceOf(ShortUrlNotFoundException.class);

        verifyNoInteractions(clickRecorder);
        // Positive control: an ACTIVE link with the same service instance is recorded.
        stored("act1234", "https://example.com/ok", ShortUrlStatus.ACTIVE);
        service.resolveAndRecordClick("act1234");
        verify(clickRecorder, times(1)).record(LINK_ID, CLICK_AT);
    }

    private static PSQLException psqlWithMarker(String sqlState) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C" + sqlState + "\0Mfailing row " + URL_MARKER + "\0"),
                true);
    }

    @Test
    void shouldFailOpenAndLogOnlyCodeIdClassAndSqlStateWhenTheRecorderFails() {
        String url = "https://secret-host.example/caf\u00e9?token=marker";
        stored("abc1234", url, ShortUrlStatus.ACTIVE);
        PSQLException psql = psqlWithMarker("23503");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("insert failed for " + url, psql);
        doThrow(failure).when(clickRecorder).record(LINK_ID, CLICK_AT);

        String target = service.resolveAndRecordClick("abc1234");

        assertThat(target).isEqualTo(url);
        // Positive controls: the marker and the URL really are in the exception chain, so their absence below is
        // meaningful.
        assertThat(failure.getMessage()).contains("marker").contains(url);
        assertThat(psql.getMessage()).contains("marker");
        assertThat(LocationEncoder.encode(url)).isNotEqualTo(url).contains("caf%C3%A9");
        // Positive capture: exactly one WARN line, with the four allowed fields.
        List<ILoggingEvent> warnings = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        ILoggingEvent warn = warnings.get(0);
        assertThat(warn.getFormattedMessage()).isEqualTo("Click not recorded: code=abc1234 id=" + LINK_ID
                + " exception=DataIntegrityViolationException sqlState=23503");
        assertThat(warn.getThrowableProxy()).isNull();
        assertThat(warn.getArgumentArray()).allSatisfy(a -> assertThat(a).isNotInstanceOf(Throwable.class));
        // Absence: neither the raw nor the encoded URL, nor any fragment of it or of the message, anywhere in the logs.
        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
        String everything = logged() + warn.getMessage() + warn.getMDCPropertyMap();
        assertThat(everything).doesNotContain(url).doesNotContain(LocationEncoder.encode(url))
                .doesNotContain("marker").doesNotContain("secret-host").doesNotContain("https://")
                .doesNotContain("caf%C3%A9").doesNotContain("insert failed");
    }

    @Test
    void shouldLogSqlStateNoneWhenNoPostgresExceptionIsInTheChain() {
        stored("abc1234", URL_MARKER, ShortUrlStatus.ACTIVE);
        doThrow(new IllegalStateException("boom " + URL_MARKER)).when(clickRecorder).record(LINK_ID, CLICK_AT);

        String target = service.resolveAndRecordClick("abc1234");

        assertThat(target).isEqualTo(URL_MARKER);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("Click not recorded: code=abc1234 id=" + LINK_ID
                + " exception=IllegalStateException sqlState=none");
        assertThat(logs.list.get(0).getThrowableProxy()).isNull();
        assertThat(logged()).doesNotContain("marker").doesNotContain("https://").doesNotContain("boom");
    }

    @Test
    void shouldFailOpenWhenTheClockItselfFails() {
        Clock failingClock = mock(Clock.class);
        when(failingClock.instant()).thenThrow(new IllegalStateException("clock broken " + URL_MARKER));
        RedirectService failing = new RedirectService(repository, transactionManager, clickRecorder, failingClock);
        stored("abc1234", URL_MARKER, ShortUrlStatus.ACTIVE);

        assertThat(failing.resolveAndRecordClick("abc1234")).isEqualTo(URL_MARKER);

        verifyNoInteractions(clickRecorder);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logged()).contains("exception=IllegalStateException").doesNotContain("marker");
    }

    @Test
    void shouldNotSwallowAnErrorFromTheRecorder() {
        stored("abc1234", URL_MARKER, ShortUrlStatus.ACTIVE);
        doThrow(new AssertionError("fatal")).when(clickRecorder).record(LINK_ID, CLICK_AT);

        assertThatThrownBy(() -> service.resolveAndRecordClick("abc1234")).isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldNotLogAnythingWhenARecordedClickSucceeds() {
        stored("abc1234", URL_MARKER, ShortUrlStatus.ACTIVE);

        service.resolveAndRecordClick("abc1234");

        verify(clickRecorder, times(1)).record(LINK_ID, CLICK_AT);
        assertThat(logs.list).isEmpty();
    }
}
