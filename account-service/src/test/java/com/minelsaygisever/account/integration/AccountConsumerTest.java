package com.minelsaygisever.account.integration;

import com.minelsaygisever.account.domain.Account;
import com.minelsaygisever.account.domain.enums.AccountStatus;
import com.minelsaygisever.account.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.dto.event.TransferInitiatedEvent;
import com.minelsaygisever.account.repository.AccountRepository;
import com.minelsaygisever.account.repository.OutboxRepository;
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
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = "banking.account.outbox.polling-interval=3600000")
class AccountConsumerTest {

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
    @DisplayName("Should Decrease Balance and Save Outbox when TransferInitiated Event arrives")
    void shouldConsumeTransferEvent_AndDebitAccount() {
        // 1. ARRANGE
        Account initialAccount = Account.builder()
                .customerId("CUST-001")
                .balance(new BigDecimal("1000.00"))
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        Account savedAccount = accountRepository.save(initialAccount).block();

        Assertions.assertNotNull(savedAccount);
        Long realAccountId = savedAccount.getId();

        UUID transactionId = UUID.randomUUID();


        TransferInitiatedEvent event = new TransferInitiatedEvent(
                transactionId,              // transactionId
                String.valueOf(realAccountId), // senderAccountId
                "target-account-id",     // receiverAccountId
                new BigDecimal("100.00"), // amount
                "TRY"                    // currency
        );

        // 2. ACT
        inputDestination.send(MessageBuilder.withPayload(event).build(), "processTransferInit-in-0");

        // 3. ASSERT
        await().atMost(5, SECONDS).untilAsserted(() -> {

            // A) 1000 - 100 = 900
            Account updatedAccount = accountRepository.findById(realAccountId).block();
            assertThat(updatedAccount).isNotNull();
            assertThat(updatedAccount.getBalance()).isEqualByComparingTo(new BigDecimal("900.00"));

            // B) Outbox -> ACCOUNT_DEBITED
            var outboxEntries = outboxRepository.findAll().collectList().block();
            assertThat(outboxEntries).isNotEmpty();

            var outboxItem = outboxEntries.getFirst();
            assertThat(outboxItem.getType()).isEqualTo(EventType.ACCOUNT_DEBITED);
            assertThat(outboxItem.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(outboxItem.getPayload()).contains(transactionId.toString());
        });
    }
}