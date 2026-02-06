package com.minelsaygisever.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.common.event.credit.AccountCreditedEvent;
import com.minelsaygisever.common.event.debit.AccountDebitFailedEvent;
import com.minelsaygisever.common.event.refund.AccountRefundedEvent;
import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.repository.TransferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferSagaOrchestratorTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private ObjectMapper objectMapper;

    private SimpleMeterRegistry meterRegistry;

    private TransferSagaOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        TransactionalOperator txOp = mock(TransactionalOperator.class);
        when(txOp.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));

        orchestrator = new TransferSagaOrchestrator(
                transferRepository, outboxRepository, objectMapper, txOp, meterRegistry
        );
    }

    @Test
    @DisplayName("Metrics: Should record timer when Credit Success (Happy Path)")
    void shouldRecordTimer_WhenSagaCompletes() {
        // Arrange
        UUID txId = UUID.randomUUID();
        LocalDateTime startTime = LocalDateTime.now().minusSeconds(2);

        Transfer transfer = Transfer.builder()
                .id(1L)
                .transactionId(txId)
                .state(TransferState.DEBITED)
                .currency("USD")
                .createdAt(startTime)
                .build();

        when(transferRepository.findByTransactionId(txId)).thenReturn(Mono.just(transfer));
        when(transferRepository.save(any())).thenReturn(Mono.just(transfer));

        AccountCreditedEvent event = new AccountCreditedEvent(txId, "receiver", BigDecimal.TEN, "USD");

        // Act
        StepVerifier.create(orchestrator.handleCreditSuccess(event))
                .verifyComplete();

        // Assert Metrics
        assertThat(meterRegistry.getMeters()).isNotEmpty();

        var timer = meterRegistry.find("money.transfer.saga.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(1000);
        assertThat(timer.getId().getTag("status")).isEqualTo("success");
    }

    @Test
    @DisplayName("Metrics: Should increment counter when Refund Success (Unhappy Path)")
    void shouldIncrementCounter_WhenRefundCompletes() {
        // Arrange
        UUID txId = UUID.randomUUID();
        Transfer transfer = Transfer.builder()
                .id(1L)
                .transactionId(txId)
                .state(TransferState.REFUND_INITIATED)
                .currency("TRY")
                .createdAt(LocalDateTime.now())
                .failureReason("User not found")
                .build();

        when(transferRepository.findByTransactionId(txId)).thenReturn(Mono.just(transfer));
        when(transferRepository.save(any())).thenReturn(Mono.just(transfer));

        AccountRefundedEvent event = new AccountRefundedEvent(txId, "sender", BigDecimal.TEN, "TRY");

        // Act
        StepVerifier.create(orchestrator.handleRefundSuccess(event))
                .verifyComplete();

        // Assert Counter
        var counter = meterRegistry.find("money.transfer.refund.count").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTag("currency")).isEqualTo("TRY");

        // Assert Timer
        var timer = meterRegistry.find("money.transfer.saga.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.getId().getTag("status")).isEqualTo("refunded");
    }

    @Test
    @DisplayName("Metrics: Should record timer when Debit Fails (Business Error)")
    void shouldRecordTimer_WhenDebitFails() {
        // Arrange
        UUID txId = UUID.randomUUID();
        Transfer transfer = Transfer.builder()
                .id(1L)
                .transactionId(txId)
                .state(TransferState.STARTED)
                .currency("EUR")
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .build();

        when(transferRepository.findByTransactionId(txId)).thenReturn(Mono.just(transfer));
        when(transferRepository.save(any())).thenReturn(Mono.just(transfer));

        AccountDebitFailedEvent event = new AccountDebitFailedEvent(txId, "sender", BigDecimal.TEN, "EUR", "Insufficient Funds");

        // Act
        StepVerifier.create(orchestrator.handleDebitFail(event))
                .verifyComplete();

        // Assert Timer
        var timer = meterRegistry.find("money.transfer.saga.duration").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);

        assertThat(timer.getId().getTag("status")).isEqualTo("failed_debit");
    }
}