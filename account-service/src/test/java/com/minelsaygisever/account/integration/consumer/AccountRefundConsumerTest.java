package com.minelsaygisever.account.integration.consumer;

import com.minelsaygisever.account.domain.Account;
import com.minelsaygisever.account.domain.enums.AccountStatus;
import com.minelsaygisever.account.domain.enums.EventType;
import com.minelsaygisever.account.dto.event.refund.TransferRefundRequestedEvent;
import com.minelsaygisever.account.repository.AccountRepository;
import com.minelsaygisever.account.repository.OutboxRepository;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.support.MessageBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestChannelBinderConfiguration.class)
class AccountRefundConsumerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withInitScript("schema.sql");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:alpine"))
            .withExposedPorts(6379);

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private InputDestination inputDestination;

    @BeforeEach
    void setup() {
        accountRepository.deleteAll().block();
        outboxRepository.deleteAll().block();
    }

    @Test
    @DisplayName("Should Refund Balance and Save Outbox when Refund Requested")
    void shouldConsumeRefundEvent_AndCreditAccount() {
        // 1. ARRANGE
        Account senderAccount = accountRepository.save(Account.builder()
                .customerId("SENDER-001")
                .balance(new BigDecimal("900.00"))
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build()).block();

        Assertions.assertNotNull(senderAccount);
        Long accountId = senderAccount.getId();
        UUID transactionId = UUID.randomUUID();

        // 100 TL refund
        TransferRefundRequestedEvent event = new TransferRefundRequestedEvent(
                transactionId,
                String.valueOf(accountId),
                new BigDecimal("100.00"),
                "TRY",
                "Receiver Not Found"
        );

        // 2. ACT
        inputDestination.send(MessageBuilder.withPayload(event).build(), "transferRefundRequested-in-0");

        // 3. ASSERT
        await().atMost(5, SECONDS).untilAsserted(() -> {
            // A) 900 + 100 = 1000
            Account updatedAccount = accountRepository.findById(accountId).block();
            assertThat(updatedAccount).isNotNull();
            assertThat(updatedAccount.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));

            // B) Outbox -> ACCOUNT_REFUNDED
            var outboxEntries = outboxRepository.findAll().collectList().block();
            assertThat(outboxEntries).isNotEmpty();
            assertThat(outboxEntries.getFirst().getType()).isEqualTo(EventType.ACCOUNT_REFUNDED);
        });
    }

    @Test
    @SneakyThrows
    @DisplayName("Idempotency: Duplicate Refund Events -> Balance Refunded ONCE")
    void shouldHandleDuplicateRefund_Idempotently() {
        // 1. ARRANGE
        Account senderAccount = accountRepository.save(Account.builder()
                .customerId("SENDER-IDEM")
                .balance(new BigDecimal("900.00"))
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build()).block();

        Assertions.assertNotNull(senderAccount);
        Long accountId = senderAccount.getId();
        UUID transactionId = UUID.randomUUID();

        TransferRefundRequestedEvent event = new TransferRefundRequestedEvent(
                transactionId,
                String.valueOf(accountId),
                new BigDecimal("100.00"),
                "TRY",
                "Duplicate Test"
        );

        // 2. ACT - First Event
        inputDestination.send(MessageBuilder.withPayload(event).build(), "transferRefundRequested-in-0");

        // Assert: 900 -> 1000
        await().atMost(5, SECONDS).untilAsserted(() -> {
            Account updated = accountRepository.findById(accountId).block();
            Assertions.assertNotNull(updated);
            assertThat(updated.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        });

        // 3. ACT - Duplicate Event
        inputDestination.send(MessageBuilder.withPayload(event).build(), "transferRefundRequested-in-0");

        // 4. ASSERT - still 1000
        Thread.sleep(1000);

        Account finalAccount = accountRepository.findById(accountId).block();
        Assertions.assertNotNull(finalAccount);
        assertThat(finalAccount.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }
}
