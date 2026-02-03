package com.minelsaygisever.transfer.job;

import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.integration.AbstractIntegrationTest;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class SagaReconciliationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaReconciliationJob reconciliationJob;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setup() {
        transferRepository.deleteAll().block();
        outboxRepository.deleteAll().block();
        redisTemplate.execute(conn -> conn.serverCommands().flushAll()).blockLast();
    }

    @Test
    @DisplayName("JOB: Should detect stuck DEBITED transfer and trigger REFUND")
    void shouldDetectAndRollbackStuckTransfer() {
        Transfer transfer = Transfer.builder()
                .idempotencyKey("stuck-key-1")
                .transactionId(UUID.randomUUID())
                .senderAccountId("A")
                .receiverAccountId("B")
                .amount(BigDecimal.valueOf(100))
                .currency("TRY")
                .requestHash("dummy-hash-123")
                .state(TransferState.DEBITED)
                .createdAt(LocalDateTime.now())
                .build();

        Transfer savedTransfer = transferRepository.save(transfer).block();
        assertThat(savedTransfer).isNotNull();

        // TIME TRAVEL
        LocalDateTime oldDate = LocalDateTime.now().minusMinutes(10);

        Long updatedRows = databaseClient.sql("UPDATE transfers SET updated_at = $1 WHERE id = $2")
                .bind(0, oldDate)
                .bind(1, savedTransfer.getId())
                .fetch()
                .rowsUpdated()
                .block();

        assertThat(updatedRows).isEqualTo(1);

        // ACT
        reconciliationJob.scanStuckTransfers();

        await()
            .atMost(java.time.Duration.ofSeconds(5))
            .pollInterval(java.time.Duration.ofMillis(100))
            .untilAsserted(() -> {
                Transfer updatedTransfer = transferRepository.findById(savedTransfer.getId()).block();

                assertThat(updatedTransfer).isNotNull();
                assertThat(updatedTransfer.getState()).isEqualTo(TransferState.REFUND_INITIATED);
                assertThat(updatedTransfer.getFailureReason()).isNotNull();
                assertThat(updatedTransfer.getFailureReason()).contains("Saga Timeout");
            });

        await()
            .atMost(java.time.Duration.ofSeconds(5))
            .untilAsserted(() -> {
                Boolean hasRefundEvent = outboxRepository.findAll()
                        .any(outbox ->
                                outbox.getType() == EventType.TRANSFER_REFUND_REQUESTED &&
                                        outbox.getPayload().contains(savedTransfer.getTransactionId().toString())
                        ).block();

                assertThat(hasRefundEvent).isTrue();
            });
    }

    @Test
    @DisplayName("JOB: Should IGNORE recent transfers (Not stuck)")
    void shouldIgnoreRecentTransfers() {
        Transfer transfer = Transfer.builder()
                .idempotencyKey("fresh-key-1")
                .transactionId(UUID.randomUUID())
                .senderAccountId("A")
                .receiverAccountId("B")
                .amount(BigDecimal.valueOf(50))
                .currency("EUR")
                .requestHash("dummy-hash-456")
                .state(TransferState.DEBITED)
                .createdAt(LocalDateTime.now())
                .build();

        Transfer savedTransfer = transferRepository.save(transfer).block();

        // ACT
        reconciliationJob.scanStuckTransfers();

        Assertions.assertNotNull(savedTransfer);
        StepVerifier.create(transferRepository.findById(savedTransfer.getId()))
                .expectNextMatches(t -> t.getState() == TransferState.DEBITED)
                .verifyComplete();

        StepVerifier.create(outboxRepository.count())
                .expectNext(0L)
                .verifyComplete();
    }

    @Test
    @DisplayName("JOB: Should RETRY (Resend Event) when stuck but NOT yet expired")
    void shouldRetryRefund_WhenStuckButNotExpired() {
        Transfer transfer = Transfer.builder()
                .idempotencyKey("retry-key-1")
                .transactionId(UUID.randomUUID())
                .senderAccountId("A")
                .receiverAccountId("B")
                .amount(BigDecimal.valueOf(25))
                .currency("USD")
                .requestHash("hash-retry")
                .state(TransferState.REFUND_INITIATED)
                .createdAt(LocalDateTime.now())
                .build();

        Transfer savedTransfer = transferRepository.save(transfer).block();

        // TIME TRAVEL
        LocalDateTime createdAtOld = LocalDateTime.now().minusMinutes(30);
        LocalDateTime updatedAtOld = LocalDateTime.now().minusMinutes(10);

        Assertions.assertNotNull(savedTransfer);
        databaseClient.sql("UPDATE transfers SET created_at = $1, updated_at = $2 WHERE id = $3")
                .bind(0, createdAtOld)
                .bind(1, updatedAtOld)
                .bind(2, savedTransfer.getId())
                .fetch()
                .rowsUpdated()
                .block();

        // ACT
        reconciliationJob.scanStuckTransfers();

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .pollInterval(java.time.Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Boolean hasNewEvent = outboxRepository.findAll()
                            .any(outbox ->
                                    outbox.getType() == EventType.TRANSFER_REFUND_REQUESTED &&
                                            outbox.getPayload().contains(savedTransfer.getTransactionId().toString())
                            ).block();
                    assertThat(hasNewEvent).as("New Refund Event should be published").isTrue();

                    Transfer currentState = transferRepository.findById(savedTransfer.getId()).block();
                    Assertions.assertNotNull(currentState);
                    assertThat(currentState.getState()).isEqualTo(TransferState.REFUND_INITIATED);
                    assertThat(currentState.getUpdatedAt()).isAfter(updatedAtOld);
                });
    }

    @Test
    @DisplayName("JOB: Should GIVE UP and mark FAILED when transfer is stuck too long (Hard Timeout)")
    void shouldGiveUpAndFail_WhenStuckBeyondMaxRetryDuration() {
        Transfer transfer = Transfer.builder()
                .idempotencyKey("ancient-key-1")
                .transactionId(UUID.randomUUID())
                .senderAccountId("A")
                .receiverAccountId("B")
                .amount(BigDecimal.valueOf(75))
                .currency("EUR")
                .requestHash("hash-ancient")
                .state(TransferState.REFUND_INITIATED)
                .createdAt(LocalDateTime.now())
                .build();

        Transfer savedTransfer = transferRepository.save(transfer).block();
        assertThat(savedTransfer).isNotNull();

        // TIME TRAVEL
        LocalDateTime createdAtOld = LocalDateTime.now().minusHours(2);
        LocalDateTime updatedAtOld = LocalDateTime.now().minusMinutes(10);

        Long updatedRows = databaseClient.sql("UPDATE transfers SET created_at = $1, updated_at = $2 WHERE id = $3")
                .bind(0, createdAtOld)
                .bind(1, updatedAtOld)
                .bind(2, savedTransfer.getId())
                .fetch()
                .rowsUpdated()
                .block();

        assertThat(updatedRows).isEqualTo(1);

        // ACT
        reconciliationJob.scanStuckTransfers();

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .pollInterval(java.time.Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Transfer finalState = transferRepository.findById(savedTransfer.getId()).block();
                    assertThat(finalState).isNotNull();
                    assertThat(finalState.getState()).isEqualTo(TransferState.REFUND_FAILED);
                });
    }
}