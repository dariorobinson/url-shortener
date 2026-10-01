package com.schwab.urlshortener.util.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.schwab.urlshortener.repository.ClickEventRepository;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.util.domain.ClickEvent;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * The recorder's transaction and ordering (AC1, AC3; D16, D27, D45, D91) against mocks, with a real
 * {@code TransactionTemplate} on a mock transaction manager.
 */
class JpaClickRecorderTest {

    private static final long ID = 42L;
    /** Sub-microsecond input: PostgreSQL would round ...123456789 up to ...123457, so the recorder must truncate. */
    private static final Instant NANOS = Instant.parse("2026-03-01T10:15:30.123456789Z");
    private static final Instant MICROS = Instant.parse("2026-03-01T10:15:30.123456Z");

    private final ShortUrlRepository shortUrls = mock(ShortUrlRepository.class);
    private final ClickEventRepository events = mock(ClickEventRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private JpaClickRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new JpaClickRecorder(shortUrls, events, transactionManager);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    }

    @Test
    void shouldRunInAReadWriteRequiresNewTransactionWithDefaultIsolationAndNoTimeout() {
        when(shortUrls.recordClick(ID, MICROS)).thenReturn(1);

        recorder.record(ID, NANOS);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(1)).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isFalse();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(definition.getValue().getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
    }

    @Test
    void shouldUpdateThenInsertWithTheSameMicrosecondTruncatedInstantAndCommit() {
        when(shortUrls.recordClick(ID, MICROS)).thenReturn(1);

        recorder.record(ID, NANOS);

        InOrder order = inOrder(shortUrls, events, transactionManager);
        order.verify(shortUrls).recordClick(ID, MICROS);
        ArgumentCaptor<ClickEvent> event = ArgumentCaptor.forClass(ClickEvent.class);
        order.verify(events).saveAndFlush(event.capture());
        order.verify(transactionManager).commit(transactionStatus);
        assertThat(event.getValue().getShortUrlId()).isEqualTo(ID);
        assertThat(event.getValue().getClickedAt()).isEqualTo(MICROS).isNotEqualTo(NANOS);
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void shouldSkipTheInsertAndStillCommitWhenTheUpdateChangedNoRow() {
        when(shortUrls.recordClick(ID, MICROS)).thenReturn(0);

        recorder.record(ID, NANOS);

        verify(shortUrls, times(1)).recordClick(ID, MICROS);
        verifyNoInteractions(events);
        verify(transactionManager, times(1)).commit(transactionStatus);
        verify(transactionManager, never()).rollback(any());
        // Positive control: the same recorder does insert when the UPDATE changes a row.
        when(shortUrls.recordClick(ID, MICROS)).thenReturn(1);
        recorder.record(ID, NANOS);
        verify(events, times(1)).saveAndFlush(any(ClickEvent.class));
    }

    @Test
    void shouldRollBackAndPropagateWhenTheInsertFails() {
        when(shortUrls.recordClick(ID, MICROS)).thenReturn(1);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("insert failed");
        when(events.saveAndFlush(any(ClickEvent.class))).thenThrow(failure);

        assertThatThrownBy(() -> recorder.record(ID, NANOS)).isSameAs(failure);

        verify(transactionManager, times(1)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void shouldRollBackAndPropagateWhenTheUpdateFails() {
        IllegalStateException failure = new IllegalStateException("update failed");
        when(shortUrls.recordClick(ID, MICROS)).thenThrow(failure);

        assertThatThrownBy(() -> recorder.record(ID, NANOS)).isSameAs(failure);

        verify(transactionManager, times(1)).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(events);
    }

    @Test
    void shouldNotBeTransactionalAtClassOrMethodLevel() {
        assertThat(JpaClickRecorder.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(JpaClickRecorder.class.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
        Method[] methods = JpaClickRecorder.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods).map(Method::getName)).contains("record");
        for (Method method : methods) {
            assertThat(method.isAnnotationPresent(Transactional.class)).as(method.getName()).isFalse();
            assertThat(method.isAnnotationPresent(jakarta.transaction.Transactional.class))
                    .as(method.getName()).isFalse();
        }
    }
}
