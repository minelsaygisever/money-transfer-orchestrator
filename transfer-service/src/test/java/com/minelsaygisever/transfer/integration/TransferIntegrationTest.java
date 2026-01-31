package com.minelsaygisever.transfer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.transfer.domain.enums.AggregateType;
import com.minelsaygisever.transfer.domain.enums.EventType;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import com.minelsaygisever.transfer.dto.TransferApiRequest;
import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.dto.TransferResponse;
import com.minelsaygisever.transfer.dto.event.TransferInitiatedEvent;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.repository.TransferRepository;
import com.minelsaygisever.transfer.util.IdempotencyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
@Import(TestChannelBinderConfiguration.class)
@TestPropertySource(properties = "transfer.outbox.polling-interval=1000000")
class TransferIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withInitScript("schema.sql");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:alpine"))
            .withExposedPorts(6379);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        transferRepository.deleteAll().block();
        outboxRepository.deleteAll().block();
        redisTemplate.execute(conn -> conn.serverCommands().flushAll()).blockLast();
    }

    @Test
    @DisplayName("E2E: Should initiate transfer AND save Outbox event successfully")
    void shouldInitiateTransfer_AndCreateOutboxRecord() {
        // 1. Arrange
        String idempotencyKey = UUID.randomUUID().toString();
        TransferApiRequest request = new TransferApiRequest(
                "1", "2", new BigDecimal("100.00"), "TRY"
        );

        // 2. Act
        webTestClient.post()
                .uri("/api/v1/transfers")
                .header("x-idempotency-key", idempotencyKey)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TransferResponse.class)
                .value(response -> {
                    assertThat(response.state().name()).isEqualTo("STARTED");
                    assertThat(response.transactionId()).isNotNull();
                });

        // 3. Assert (Database & Outbox Control)
        StepVerifier.create(transferRepository.findAll())
                .expectNextMatches(transfer ->
                        transfer.getIdempotencyKey().equals(idempotencyKey) &&
                                transfer.getAmount().compareTo(new BigDecimal("100.00")) == 0
                )
                .verifyComplete();

        StepVerifier.create(outboxRepository.findAll())
                .expectNextMatches(outbox -> {
                    assertThat(outbox.getAggregateType()).isEqualTo(AggregateType.TRANSFER);
                    assertThat(outbox.getType()).isEqualTo(EventType.TRANSFER_INITIATED);
                    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);

                    // Payload (JSON)
                    try {
                        TransferInitiatedEvent event = objectMapper.readValue(
                                outbox.getPayload(),
                                TransferInitiatedEvent.class
                        );
                        assertThat(event.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
                        assertThat(event.currency()).isEqualTo("TRY");
                        return true;
                    } catch (Exception e) {
                        throw new RuntimeException("Outbox JSON parsing failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Hasher: should throw when amount has >2 decimals")
    void shouldThrow_WhenAmountScaleIsGreaterThan2() {
        TransferCommand cmd = new TransferCommand(
                "key",
                "1",
                "2",
                new BigDecimal("10.123"),
                "try"
        );

        assertThatThrownBy(() -> IdempotencyHasher.hash(cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");
    }

    @Test
    @DisplayName("Idempotency: Same Key same payload should return Same Response (No Error)")
    void shouldReturnSameResponse_WhenIdempotencyKeyIsSame() {
        String idempotencyKey = UUID.randomUUID().toString();
        TransferApiRequest request = new TransferApiRequest(
                "1", "2", new BigDecimal("50.00"), "USD"
        );

        // 1. Request
        TransferResponse firstResponse = webTestClient.post()
                .uri("/api/v1/transfers")
                .header("x-idempotency-key", idempotencyKey)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .returnResult(TransferResponse.class)
                .getResponseBody()
                .blockFirst();

        assertThat(firstResponse).isNotNull();

        // 2. Same Request
        webTestClient.post()
                .uri("/api/v1/transfers")
                .header("x-idempotency-key", idempotencyKey)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk() // Should return 200 OK
                .expectBody(TransferResponse.class)
                .value(secondResponse -> {
                    assertThat(secondResponse).isNotNull();
                    assertThat(secondResponse.transactionId()).isEqualTo(firstResponse.transactionId());
                    assertThat(secondResponse.createdAt()).isEqualTo(firstResponse.createdAt());
                });

        // Verify that there is only 1 record in the DB.
        StepVerifier.create(transferRepository.count())
                .expectNext(1L)
                .verifyComplete();

        StepVerifier.create(outboxRepository.count())
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    @DisplayName("Idempotency Hardening: Same key with DIFFERENT payload -> 409 Conflict (Key Reuse)")
    void shouldReturn409_WhenSameIdempotencyKeyUsedWithDifferentPayload() {
        String idempotencyKey = UUID.randomUUID().toString();

        // 1) First request (OK)
        TransferApiRequest first = new TransferApiRequest(
                "1", "2", new BigDecimal("50.00"), "USD"
        );

        TransferResponse firstResponse = webTestClient.post()
                .uri("/api/v1/transfers")
                .header("x-idempotency-key", idempotencyKey)
                .bodyValue(first)
                .exchange()
                .expectStatus().isOk()
                .returnResult(TransferResponse.class)
                .getResponseBody()
                .blockFirst();

        assertThat(firstResponse).isNotNull();

        // 2) Same key but DIFFERENT payload (amount changed -> must be 409)
        TransferApiRequest second = new TransferApiRequest(
                "1", "2", new BigDecimal("60.00"), "USD" // <-- changed amount
        );

        webTestClient.post()
                .uri("/api/v1/transfers")
                .header("x-idempotency-key", idempotencyKey)
                .bodyValue(second)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("IDEMPOTENCY_KEY_REUSE");

        // 3) DB must still have only 1 transfer + 1 outbox
        StepVerifier.create(transferRepository.count())
                .expectNext(1L)
                .verifyComplete();

        StepVerifier.create(outboxRepository.count())
                .expectNext(1L)
                .verifyComplete();

        // (Optional) Ensure the stored transfer is still the original one
        StepVerifier.create(transferRepository.findByIdempotencyKey(idempotencyKey))
                .expectNextMatches(t ->
                        t.getAmount().compareTo(new BigDecimal("50.00")) == 0 &&
                                "USD".equalsIgnoreCase(t.getCurrency())
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("Race Condition: 5 Concurrent requests with SAME key -> Only 1 Success")
    void shouldHandleConcurrentRequests_WithSameIdempotencyKey() {
        String idempotencyKey = "RACE-CONDITION-KEY-" + UUID.randomUUID();
        TransferApiRequest request = new TransferApiRequest(
                "A", "B", new BigDecimal("500.00"), "EUR"
        );

        int parallelRequests = 5;

        Flux<Integer> responses = Flux.range(0, parallelRequests)
                .parallel()
                .runOn(Schedulers.boundedElastic())
                .flatMap(i -> webTestClient.post()
                        .uri("/api/v1/transfers")
                        .header("x-idempotency-key", idempotencyKey)
                        .bodyValue(request)
                        .exchange()
                        .returnResult(TransferResponse.class)
                        .getResponseBody()
                        .map(r -> {
                            return 200;
                        })
                        .onErrorResume(e -> {
                            return Mono.just(409);
                        })
                )
                .sequential();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        StepVerifier.create(responses)
                .recordWith(java.util.ArrayList::new)
                .expectNextCount(parallelRequests)
                .consumeRecordedWith(results -> {
                    results.forEach(code -> {
                        if (code == 200) successCount.incrementAndGet();
                        else if (code == 409) conflictCount.incrementAndGet();
                    });
                })
                .verifyComplete();

        System.out.println("Success: " + successCount.get());
        System.out.println("Conflict: " + conflictCount.get());

        // there should be ONLY 1 record in the database.
        StepVerifier.create(transferRepository.findAll())
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(outboxRepository.count())
                .expectNext(1L)
                .verifyComplete();
    }
}