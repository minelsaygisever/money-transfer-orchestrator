package com.minelsaygisever.account.integration;

import com.minelsaygisever.account.dto.AccountDto;
import com.minelsaygisever.account.dto.CreateAccountRequest;
import com.minelsaygisever.account.repository.AccountRepository;
import com.minelsaygisever.account.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Objects;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestChannelBinderConfiguration.class)
public class AccountIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("schema.sql");

    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setup() {
        accountRepository.deleteAll().block();
    }

    @Test
    void shouldCreateAccount_AndSaveToDatabase_WithNormalizedCurrency() {
        CreateAccountRequest request = new CreateAccountRequest(
                "12345",
                new BigDecimal("100.00"),
                "try"
        );
        var createMono = accountService.create(request);

        StepVerifier.create(createMono)
                .expectNextMatches(accountDto ->
                        accountDto.customerId().equals("12345") &&
                                accountDto.currency().equals("TRY") &&
                                accountDto.balance().compareTo(new BigDecimal("100.00")) == 0
                )
                .verifyComplete();

        StepVerifier.create(accountRepository.findAll())
                .expectNextMatches(account ->
                        account.getCustomerId().equals("12345") &&
                                account.getCurrency().equals("TRY") &&
                                account.getBalance().compareTo(new BigDecimal("100.00")) == 0
                )
                .verifyComplete();
    }

    @Test
    void shouldWithdrawMoney_WhenBalanceIsSufficient() {
        CreateAccountRequest request = new CreateAccountRequest("999", new BigDecimal("500.00"), "USD");

        AccountDto createdAccount = accountService.create(request).block();
        Objects.requireNonNull(createdAccount, "The account could not be created during setup; the returned value is null!");
        String accountId = createdAccount.id();

        var withdrawMono = accountService.withdraw(accountId, new BigDecimal("200.00"), "USD");

        StepVerifier.create(withdrawMono)
                .verifyComplete();

        StepVerifier.create(accountRepository.findById(Long.valueOf(accountId)))
                .expectNextMatches(acc ->
                        acc.getBalance().compareTo(new BigDecimal("300.00")) == 0
                )
                .verifyComplete();
    }

    @Test
    void shouldHandleConcurrentUpdates_WhenOptimisticLockingOccurs() {
        CreateAccountRequest request = new CreateAccountRequest("CONCURRENT_USER", new BigDecimal("1000.00"), "TRY");

        AccountDto account = accountService.create(request).block();
        Objects.requireNonNull(account, "The account could not be created during setup; the returned value is null!");
        String accountId = account.id();

        // 3 different threads are trying to withdraw 200 TL simultaneously.
        Mono<Void> tx1 = accountService.withdraw(accountId, new BigDecimal("200.00"), "TRY");
        Mono<Void> tx2 = accountService.withdraw(accountId, new BigDecimal("200.00"), "TRY");
        Mono<Void> tx3 = accountService.withdraw(accountId, new BigDecimal("200.00"), "TRY");
        StepVerifier.create(Mono.when(tx1, tx2, tx3))
                .verifyComplete();

        StepVerifier.create(accountRepository.findById(Long.valueOf(accountId)))
                .expectNextMatches(acc -> {
                    System.out.println("Final Balance: " + acc.getBalance());
                    return acc.getBalance().compareTo(new BigDecimal("400.00")) == 0;
                })
                .verifyComplete();
    }
}