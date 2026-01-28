package com.minelsaygisever.account.service;

import com.minelsaygisever.account.domain.Account;
import com.minelsaygisever.account.domain.AccountStatus;
import com.minelsaygisever.account.dto.AccountDto;
import com.minelsaygisever.account.exception.AccountNotActiveException;
import com.minelsaygisever.account.exception.AccountNotFoundException;
import com.minelsaygisever.account.exception.DailyLimitExceededException;
import com.minelsaygisever.account.exception.InsufficientBalanceException;
import com.minelsaygisever.account.repository.AccountRepository;
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

    public Mono<AccountDto> create(String customerId, BigDecimal initialAmount, String currency) {
        Account account = Account.builder()
                .customerId(customerId)
                .balance(initialAmount)
                .currency(currency)
                .status(AccountStatus.ACTIVE)
                .dailyLimit(BigDecimal.valueOf(5000))
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
    public Mono<Void> addMoney(String id, BigDecimal amount) {
        return accountRepository.findById(Long.valueOf(id))
                .flatMap(account -> {
                    if (account.getStatus() != AccountStatus.ACTIVE) {
                        return Mono.error(new AccountNotActiveException(id, "Account " + id + " is not ACTIVE!"));
                    }

                    account.setBalance(account.getBalance().add(amount));
                    return accountRepository.save(account);
                })
                .retryWhen(Retry.backoff(10, Duration.ofMillis(50))
                        .maxBackoff(Duration.ofMillis(500))
                        .jitter(0.75)
                        .filter(throwable -> throwable instanceof OptimisticLockingFailureException))
                .then();
    }

    @Transactional
    public Mono<Void> withdraw(String id, BigDecimal amount) {
        return accountRepository.findById(Long.valueOf(id))
                .flatMap(account -> {
                    if (AccountStatus.ACTIVE != account.getStatus()) {
                        return Mono.error(new AccountNotActiveException(id, "Account " + id + " is not ACTIVE!"));
                    }

                    if (account.getBalance().compareTo(amount) < 0) {
                        return Mono.error(new InsufficientBalanceException(id, "Insufficient funds for Account " + id));
                    }

                    if (account.getDailyLimit() != null && amount.compareTo(account.getDailyLimit()) > 0) {
                        return Mono.error(new DailyLimitExceededException(id, "Daily limit exceeded for Account " + id));
                    }

                    account.setBalance(account.getBalance().subtract(amount));
                    return accountRepository.save(account);
                })
                .retryWhen(Retry.backoff(3, Duration.ofMillis(100))
                        .filter(throwable -> throwable instanceof OptimisticLockingFailureException))
                .then();
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
