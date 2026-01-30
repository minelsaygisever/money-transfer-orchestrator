package com.minelsaygisever.account.service;

import com.minelsaygisever.account.config.AccountProperties;
import com.minelsaygisever.account.domain.Account;
import com.minelsaygisever.account.domain.enums.AccountStatus;
import com.minelsaygisever.account.dto.AccountDto;
import com.minelsaygisever.account.dto.CreateAccountRequest;
import com.minelsaygisever.account.exception.AccountNotActiveException;
import com.minelsaygisever.account.exception.AccountNotFoundException;
import com.minelsaygisever.account.exception.DailyLimitExceededException;
import com.minelsaygisever.account.exception.InsufficientBalanceException;
import com.minelsaygisever.account.repository.AccountRepository;
import com.minelsaygisever.common.exception.CurrencyMismatchException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountProperties properties;

    public Mono<AccountDto> create(CreateAccountRequest request) {
        String normalizedCurrency = request.currency().toUpperCase();
        BigDecimal limit = properties.defaultDailyLimit();

        Account account = Account.builder()
                .customerId(request.customerId())
                .balance(request.initialAmount())
                .currency(normalizedCurrency)
                .status(AccountStatus.ACTIVE)
                .dailyLimit(limit)
                .build();

        return accountRepository.save(account)
                .map(this::mapToDto);
    }

    public Mono<AccountDto> findById(String id) {
        return accountRepository.findById(Long.valueOf(id))
                .map(this::mapToDto)
                .switchIfEmpty(Mono.error(new AccountNotFoundException(id, "Account not found with id: " + id)));
    }

    @Transactional
    public Mono<Void> addMoney(String id, BigDecimal amount, String currency) {
        return accountRepository.findById(Long.valueOf(id))
                .switchIfEmpty(Mono.error(new AccountNotFoundException(id, "Account not found with id: " + id)))
                .flatMap(account -> {
                    validateAccountActive(account);
                    validateCurrency(account, currency);

                    account.setBalance(account.getBalance().add(amount));
                    return accountRepository.save(account);
                })
                .retryWhen(retryStrategy())
                .then();
    }

    @Transactional
    public Mono<Void> withdraw(String id, BigDecimal amount, String currency) {
        System.out.println("DEBUG: withdraw called for id: " + id);

        return accountRepository.findById(Long.valueOf(id))
                .switchIfEmpty(Mono.error(new AccountNotFoundException(id, "Account not found with id: " + id)))
                .flatMap(account -> {
                    validateAccountActive(account);
                    validateCurrency(account, currency);

                    if (account.getBalance().compareTo(amount) < 0) {
                        return Mono.error(new InsufficientBalanceException(id, "Insufficient funds for Account " + id));
                    }

                    if (account.getDailyLimit() != null && amount.compareTo(account.getDailyLimit()) > 0) {
                        return Mono.error(new DailyLimitExceededException(id, "Daily limit exceeded for Account " + id));
                    }

                    account.setBalance(account.getBalance().subtract(amount));
                    return accountRepository.save(account);
                })
                .retryWhen(retryStrategy())
                .then();
    }

    private void validateAccountActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(String.valueOf(account.getId()), "Account is not ACTIVE");
        }
    }

    private void validateCurrency(Account account, String requestedCurrency) {
        if (!account.getCurrency().equalsIgnoreCase(requestedCurrency)) {
            throw new CurrencyMismatchException(
                    String.format("Account currency is %s but requested %s", account.getCurrency(), requestedCurrency)
            );
        }
    }

    private Retry retryStrategy() {
        return Retry.backoff(10, Duration.ofMillis(50))
                .filter(throwable -> throwable instanceof OptimisticLockingFailureException);
    }

    private AccountDto mapToDto(Account account) {
        return new AccountDto(
                String.valueOf(account.getId()),
                account.getCustomerId(),
                account.getBalance(),
                account.getCurrency(),
                account.getStatus(),
                account.getDailyLimit()
        );
    }
}
