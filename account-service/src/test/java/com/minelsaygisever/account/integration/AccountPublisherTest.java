package com.minelsaygisever.account.integration;

import com.minelsaygisever.account.config.AccountProperties;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.AggregateType;
import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.repository.OutboxRepository;
import com.minelsaygisever.account.service.AccountOutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestChannelBinderConfiguration.class)
class AccountPublisherTest {

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
    private AccountOutboxPublisher publisher;

    @Autowired
    private AccountProperties properties;

    @Autowired
    private TransactionalOperator transactionalOperator;

    @MockitoBean
    private StreamBridge streamBridge;

    @Captor
    private ArgumentCaptor<Message<String>> messageCaptor;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll().block();
    }

    @Test
    @DisplayName("Success Scenario: Outbox PENDING -> Kafka Send OK (Check PartitionKey) -> Status COMPLETED")
    void shouldMarkOutboxAsCompleted_WhenKafkaSendIsSuccessful() {
        // 1. ARRANGE
        String aggregateId = "TX-HAPPY-PATH";

        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.ACCOUNT)
                .aggregateId(aggregateId)
                .type(EventType.ACCOUNT_DEBITED)
                .payload("{\"amount\": 100}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outbox().bindingName()), any(Message.class))).thenReturn(true);

        // 2. ACT
        StepVerifier.create(publisher.processOutbox().as(transactionalOperator::transactional))
                .expectNextCount(1)
                .verifyComplete();

        // 3. ASSERT
        verify(streamBridge).send(eq(properties.outbox().bindingName()), messageCaptor.capture());

        Message<String> sentMessage = messageCaptor.getValue();
        assertThat(sentMessage.getHeaders().get("partitionKey"))
                .as("Partition Key must be AggregateID")
                .isEqualTo(aggregateId);

        StepVerifier.create(outboxRepository.findAll())
                .expectNextMatches(saved -> saved.getStatus() == OutboxStatus.COMPLETED)
                .verifyComplete();
    }

    @Test
    @DisplayName("Retry Logic: When Kafka fails, update RetryCount and NextAttemptTime")
    void shouldScheduleRetry_WhenStreamBridgeFails() {
        // 1. ARRANGE
        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.ACCOUNT)
                .aggregateId("TX-RETRY-TEST")
                .type(EventType.ACCOUNT_DEBITED)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outbox().bindingName()), any(Message.class))).thenReturn(false);

        // 2. ACT
        StepVerifier.create(publisher.processOutbox().as(transactionalOperator::transactional))
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
    @DisplayName("DLQ Logic: Max Retries Reached -> Mark FAILED (and send to DLQ)")
    void shouldMarkFailed_WhenMaxRetriesReached() {
        // 1. ARRANGE
        int maxRetries = properties.outbox().maxRetries();

        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.ACCOUNT)
                .aggregateId("TX-DLQ-TEST")
                .type(EventType.ACCOUNT_DEBITED)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(maxRetries) // Limit reached
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outbox().dlqBindingName()), any())).thenReturn(true);

        // 2. ACT
        StepVerifier.create(publisher.processOutbox().as(transactionalOperator::transactional))
                .expectNextCount(1)
                .verifyComplete();

        // 3. ASSERT
        StepVerifier.create(outboxRepository.findAll())
                .expectNextMatches(saved ->
                        saved.getStatus() == OutboxStatus.FAILED
                )
                .verifyComplete();

        verify(streamBridge).send(eq(properties.outbox().dlqBindingName()), any());
    }

    @Test
    @DisplayName("Concurrency: Double Execution -> Should Process Only ONCE due to Locking")
    void shouldProcessOnlyOnce_WhenCalledConcurrently() {
        // 1. ARRANGE
        Outbox outbox = Outbox.builder()
                .aggregateType(AggregateType.ACCOUNT)
                .aggregateId("TX-RACE")
                .type(EventType.ACCOUNT_DEBITED)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxRepository.save(outbox).block();

        when(streamBridge.send(eq(properties.outbox().bindingName()), any(Message.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(500);
                    return true;
                });

        // 2. ACT
        Mono<Void> execution1 = publisher.processOutbox()
                .as(transactionalOperator::transactional)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .then();

        Mono<Void> execution2 = publisher.processOutbox()
                .as(transactionalOperator::transactional)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .then();

        StepVerifier.create(Mono.when(execution1, execution2))
                .verifyComplete();

        // 3. ASSERT
        // Kafka must have only been visited once
        verify(streamBridge, times(1)).send(eq(properties.outbox().bindingName()), any(Message.class));
    }
}
