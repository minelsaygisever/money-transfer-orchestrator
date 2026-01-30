package com.minelsaygisever.transfer.integration;

import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.Outbox;
import com.minelsaygisever.transfer.domain.enums.AggregateType;
import com.minelsaygisever.transfer.domain.enums.EventType;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.service.TransferOutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "transfer.outbox-polling-interval=3600000")
class TransferPublisherTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withInitScript("schema.sql");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:alpine"))
            .withExposedPorts(6379);

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TransferOutboxPublisher publisher;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Autowired
    private TransferProperties properties;

    @MockitoBean
    private StreamBridge streamBridge;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll().block();
        redisTemplate.execute(conn -> conn.serverCommands().flushAll()).blockLast();
    }

    @Test
    @DisplayName("Success Scenario: Outbox PENDING -> Kafka Send OK -> Status COMPLETED")
    void shouldMarkOutboxAsCompleted_WhenKafkaSendIsSuccessful() {
        // 1. ARRANGE
        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.TRANSFER)
                .aggregateId("tx-1")
                .type(EventType.TRANSFER_INITIATED)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outboxBindingName()), any(Message.class))).thenReturn(true);

        // 2. ACT
        StepVerifier.create(publisher.processOutbox())
                .expectNextCount(1)
                .verifyComplete();

        // 3. ASSERT
        StepVerifier.create(outboxRepository.findAll())
                .expectNextMatches(saved -> saved.getStatus() == OutboxStatus.COMPLETED)
                .verifyComplete();

        verify(streamBridge, times(1)).send(eq(properties.outboxBindingName()), any(Message.class));
    }

    @Test
    @DisplayName("Retry Scenario: Outbox PENDING -> Kafka Fail -> Status PENDING & RetryCount Increased")
    void shouldScheduleRetry_WhenKafkaSendFails() {
        // 1. ARRANGE
        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.TRANSFER)
                .aggregateId("tx-2")
                .type(EventType.TRANSFER_INITIATED)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outboxBindingName()), any(Message.class))).thenReturn(false);

        // 2. ACT
        StepVerifier.create(publisher.processOutbox())
                .expectNextCount(1)
                .verifyComplete();

        // 3. ASSERT
        StepVerifier.create(outboxRepository.findAll())
                .expectNextMatches(saved ->
                        saved.getStatus() == OutboxStatus.PENDING &&
                                saved.getRetryCount() == 1 &&
                                saved.getNextAttemptTime().isAfter(LocalDateTime.now())
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("DLQ Scenario: Max Retries Reached -> Send to DLQ -> Status FAILED")
    void shouldMoveToDLQ_WhenMaxRetriesReached() {
        // 1. ARRANGE
        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.TRANSFER)
                .aggregateId("tx-3")
                .type(EventType.TRANSFER_INITIATED)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(5) // Limit reached
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outboxDlqBindingName()), any())).thenReturn(true);

        // 2. ACT
        StepVerifier.create(publisher.processOutbox())
                .expectNextCount(1)
                .verifyComplete();

        // 3. ASSERT
        StepVerifier.create(outboxRepository.findAll())
                .expectNextMatches(saved ->
                        saved.getStatus() == OutboxStatus.FAILED
                )
                .verifyComplete();

        verify(streamBridge).send(eq(properties.outboxDlqBindingName()), anyString());
    }

    @Test
    @DisplayName("Concurrency: Double Execution -> Should Process Only ONCE due to Locking")
    void shouldProcessOnlyOnce_WhenCalledConcurrently() {
        // 1. ARRANGE
        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.TRANSFER)
                .aggregateId("tx-race")
                .type(EventType.TRANSFER_INITIATED)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outboxBindingName()), any(Message.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(500);
                    return true;
                });

        // 2. ACT
        Mono<Void> execution1 = publisher.processOutbox()
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .then();

        Mono<Void> execution2 = publisher.processOutbox()
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .then();

        StepVerifier.create(Mono.when(execution1, execution2))
                .verifyComplete();

        // 3. ASSERT
        // Kafka must have only been visited once
        verify(streamBridge, times(1)).send(eq(properties.outboxBindingName()), any(Message.class));
    }
}