package com.minelsaygisever.transfer.integration;

import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.repository.TransferRepository;
import com.minelsaygisever.transfer.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

@SpringBootTest
@Testcontainers
class TransferResilienceTest {

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


    // Repository: Mock (For error simulation)
    @MockitoBean
    private TransferRepository transferRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Test
    @DisplayName("Resilience: Should release Redis lock when Database Save fails")
    void shouldReleaseLock_WhenDatabaseFails() {
        // Arrange
        String idempotencyKey = UUID.randomUUID().toString();
        String lockKey = "transfer_lock:" + idempotencyKey;

        TransferCommand command = new TransferCommand(
                idempotencyKey, "1", "2", new BigDecimal("100.00"), "TRY"
        );

        // Scenario: No record found in the database
        when(transferRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Mono.empty());

        // throw a RuntimeException when save() is called
        when(transferRepository.save(any())).thenReturn(Mono.error(new RuntimeException("DB Connection Lost!")));

        // Act & Assert
        StepVerifier.create(transferService.initiateTransfer(command))
                .expectErrorMessage("DB Connection Lost!")
                .verify();

        // Verification (Zombie Lock Control)
        StepVerifier.create(redisTemplate.hasKey(lockKey))
                .expectNext(false) // the key must have been removed
                .verifyComplete();
    }
}