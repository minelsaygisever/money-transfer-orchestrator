package com.minelsaygisever.transfer.integration;

import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.repository.TransferRepository;
import com.minelsaygisever.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferTransactionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withInitScript("schema.sql");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.stream.kafka.binder.brokers", kafka::getBootstrapServers);
        registry.add("transfer.outbox.polling-interval", () -> "100ms");
    }


    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    private OutboxRepository outboxRepository;

    @BeforeEach
    void setup() {
        transferRepository.deleteAll().block();
        redisTemplate.execute(conn -> conn.serverCommands().flushAll()).blockLast();
    }

    @Test
    @DisplayName("Rollback: If Outbox save fails, Transfer should be rolled back")
    void shouldRollbackTransfer_WhenOutboxFails() {
        // 1. Arrange
        String key = UUID.randomUUID().toString();
        TransferCommand command = new TransferCommand(key, "A", "B", new BigDecimal("500"), "EUR");

        when(outboxRepository.save(any()))
                .thenReturn(Mono.error(new DataIntegrityViolationException("Outbox table full!")));

        // 2. Act
        StepVerifier.create(transferService.initiateTransfer(command))
                .expectError(DataIntegrityViolationException.class)
                .verify();

        // 3. ASSERT
        StepVerifier.create(transferRepository.count())
                .expectNext(0L) // <--- ROLLBACK EVIDENCE
                .verifyComplete();
    }
}
