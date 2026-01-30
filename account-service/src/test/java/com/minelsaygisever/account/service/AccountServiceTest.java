package com.minelsaygisever.account.service;

import com.minelsaygisever.account.config.AccountProperties;
import com.minelsaygisever.account.domain.Account;
import com.minelsaygisever.account.domain.enums.AccountStatus;
import com.minelsaygisever.account.dto.CreateAccountRequest;
import com.minelsaygisever.account.exception.AccountNotActiveException;
import com.minelsaygisever.account.exception.AccountNotFoundException;
import com.minelsaygisever.account.exception.DailyLimitExceededException;
import com.minelsaygisever.account.exception.InsufficientBalanceException;
import com.minelsaygisever.account.repository.AccountRepository;
import com.minelsaygisever.common.exception.CurrencyMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountProperties accountProperties;

    @InjectMocks
    private AccountService accountService;

    // --- CREATE TESTS ---

    @Test
    @DisplayName("Create: Should return AccountDto when creation is successful")
    void create_ShouldNormalizeCurrency_AndReturnAccountDto() {
        CreateAccountRequest request = new CreateAccountRequest("1", BigDecimal.TEN, "try");
        BigDecimal mockLimit = new BigDecimal("5000.00");

        when(accountProperties.defaultDailyLimit()).thenReturn(mockLimit);

        // Arrange
        Account savedAccount = Account.builder()
                .id(1L)
                .customerId("1")
                .balance(BigDecimal.TEN)
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .build();

        when(accountRepository.save(any(Account.class))).thenReturn(Mono.just(savedAccount));

        // Act & Assert
        StepVerifier.create(accountService.create(request))
                .expectNextMatches(dto ->
                        dto.id().equals("1") &&
                                dto.currency().equals("TRY"))
                .verifyComplete();

        // Verify
        verify(accountRepository).save(argThat(acc -> acc.getCurrency().equals("TRY")));
    }

    // --- FIND BY ID TESTS ---

    @Test
    @DisplayName("FindById: Should return AccountDto when account exists")
    void findById_ShouldReturnAccountDto_WhenExists() {
        // Arrange
        Account account = Account.builder()
                .id(1L)
                .customerId("1")
                .balance(BigDecimal.ZERO)
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));

        // Act & Assert
        StepVerifier.create(accountService.findById("1"))
                .expectNextMatches(dto -> dto.id().equals("1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("FindById: Should throw AccountNotFoundException when account does not exist")
    void findById_ShouldThrowException_WhenAccountNotFound() {
        // Arrange
        when(accountRepository.findById(1L)).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(accountService.findById("1"))
                .expectError(AccountNotFoundException.class)
                .verify();
    }

    // --- WITHDRAW TESTS ---

    @Test
    @DisplayName("Withdraw: Should decrease balance and save when successful")
    void withdraw_ShouldDecreaseBalance_WhenSuccessful() {
        // Arrange: Account has 100.00
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(new BigDecimal("100.00"))
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .dailyLimit(new BigDecimal("1000.00"))
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));
        // Mock save to return the modified account
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act: Withdraw 50.00
        Mono<Void> result = accountService.withdraw("1", new BigDecimal("50.00"), "TRY");

        // Assert
        StepVerifier.create(result)
                .verifyComplete();

        // Verification: Ensure save was called with the new balance (50.00)
        verify(accountRepository).save(argThat(acc ->
                acc.getBalance().compareTo(new BigDecimal("50.00")) == 0
        ));
    }

    @Test
    @DisplayName("Withdraw: Should throw CurrencyMismatchException when currency does not match")
    void withdraw_ShouldThrowException_WhenCurrencyMismatch() {
        // Arrange: Account is USD
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(BigDecimal.valueOf(100))
                .currency("USD")
                .status(AccountStatus.ACTIVE)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));

        // Act: Try to withdraw in TRY
        Mono<Void> result = accountService.withdraw("1", BigDecimal.TEN, "TRY");

        // Assert
        StepVerifier.create(result)
                .expectError(CurrencyMismatchException.class)
                .verify();

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Withdraw: Should throw InsufficientBalanceException when balance is low")
    void withdraw_ShouldThrowException_WhenBalanceIsInsufficient() {
        // Arrange: Account has 50.00
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(BigDecimal.valueOf(50))
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));

        // Act: Try to withdraw 100.00
        Mono<Void> result = accountService.withdraw("1", BigDecimal.valueOf(100), "TRY");

        // InsufficientBalanceException
        StepVerifier.create(result)
                .expectError(InsufficientBalanceException.class)
                .verify();

        // Ensure save is NEVER called
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Withdraw: Should throw DailyLimitExceededException when amount exceeds limit")
    void withdraw_ShouldThrowException_WhenDailyLimitExceeded() {
        // Account daily limit is 1000 TL
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(BigDecimal.valueOf(5000))
                .currency("TRY")
                .dailyLimit(BigDecimal.valueOf(1000))
                .status(AccountStatus.ACTIVE)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));

        // Try to withdraw 2000 TL
        Mono<Void> result = accountService.withdraw("1", BigDecimal.valueOf(2000), "TRY");

        // DailyLimitExceededException
        StepVerifier.create(result)
                .expectError(DailyLimitExceededException.class)
                .verify();

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Withdraw: Should throw AccountNotActiveException when account is FROZEN")
    void withdraw_ShouldThrowException_WhenAccountNotActive() {
        // Arrange
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(BigDecimal.valueOf(5000))
                .currency("TRY")
                .status(AccountStatus.FROZEN) // Account is frozen
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));

        // Act
        StepVerifier.create(accountService.withdraw("1", BigDecimal.TEN, "TRY"))
                .expectError(AccountNotActiveException.class)
                .verify();

        verify(accountRepository, never()).save(any());
    }

    // --- ADD MONEY TESTS ---

    @Test
    @DisplayName("AddMoney: Should increase balance and save when successful")
    void addMoney_ShouldIncreaseBalance_WhenSuccessful() {
        // Arrange: Account has 100.00
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(new BigDecimal("100.00"))
                .currency("TRY")
                .status(AccountStatus.ACTIVE)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act: Add 50.00
        StepVerifier.create(accountService.addMoney("1", new BigDecimal("50.00"), "TRY"))
                .verifyComplete();

        // Verify: Balance should be 150.00
        verify(accountRepository).save(argThat(acc ->
                acc.getBalance().compareTo(new BigDecimal("150.00")) == 0
        ));
    }

    @Test
    @DisplayName("AddMoney: Should throw CurrencyMismatchException when currencies differ")
    void addMoney_ShouldThrowException_WhenCurrencyMismatch() {
        // Account is EUR
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(new BigDecimal("100.00"))
                .currency("EUR")
                .status(AccountStatus.ACTIVE)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));

        // Try to add USD
        StepVerifier.create(accountService.addMoney("1", BigDecimal.TEN, "USD"))
                .expectError(CurrencyMismatchException.class)
                .verify();

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("AddMoney: Should throw AccountNotActiveException when account is CLOSED")
    void addMoney_ShouldThrowException_WhenAccountIsClosed() {
        // Arrange
        Account account = Account.builder()
                .id(1L)
                .customerId("12345")
                .balance(new BigDecimal("100.00"))
                .currency("TRY")
                .status(AccountStatus.CLOSED)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Mono.just(account));

        // Act
        StepVerifier.create(accountService.addMoney("1", BigDecimal.TEN, "TRY"))
                .expectError(AccountNotActiveException.class)
                .verify();

        verify(accountRepository, never()).save(any());
    }
}