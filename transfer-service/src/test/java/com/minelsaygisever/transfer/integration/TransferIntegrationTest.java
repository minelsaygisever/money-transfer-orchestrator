package com.minelsaygisever.transfer.integration;

import com.minelsaygisever.transfer.dto.TransferApiRequest;
import com.minelsaygisever.transfer.dto.TransferResponse;
import com.minelsaygisever.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
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

    @BeforeEach
    void setup() {
        transferRepository.deleteAll().block();
    }

    @Test
    @DisplayName("Happy Path: Should initiate transfer successfully")
    void shouldInitiateTransfer() {
        String idempotencyKey = UUID.randomUUID().toString();
        TransferApiRequest request = new TransferApiRequest(
                "1", "2", new BigDecimal("100.00"), "TRY"
        );

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
    }

    @Test
    @DisplayName("Idempotency: Same Key should return Same Response (No Error)")
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
    }

    @Test
    @DisplayName("Race Condition: 5 Concurrent requests with SAME key -> Only 1 Success, others waiting or returned same")
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
    }
}