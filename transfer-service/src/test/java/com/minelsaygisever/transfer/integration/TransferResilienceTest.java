package com.minelsaygisever.transfer.integration;

import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.repository.TransferRepository;
import com.minelsaygisever.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class TransferResilienceTest extends AbstractIntegrationTest {

    // Repository: Mock (For error simulation)
    @MockitoBean
    private TransferRepository transferRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setupMock() {
        when(transferRepository.findByStateInAndUpdatedAtBefore(any(), any()))
                .thenReturn(Flux.empty());
    }

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