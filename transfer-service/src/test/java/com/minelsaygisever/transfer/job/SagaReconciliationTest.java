package com.minelsaygisever.transfer.job;

import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class SagaReconciliationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withInitScript("schema.sql");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:alpine"))
            .withExposedPorts(6379);

    @Container
    static org.testcontainers.containers.KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.stream.kafka.binder.brokers", kafka::getBootstrapServers);
        registry.add("transfer.outbox.polling-interval", () -> "100ms");
    }


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
}