package com.minelsaygisever.account.config;

import com.minelsaygisever.account.domain.Account;
import com.minelsaygisever.account.domain.AccountStatus;
import com.minelsaygisever.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountDataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;

    @Override
    public void run(String... args) {
        accountRepository.count()
                .filter(count -> count == 0)
                .flatMapMany(count -> {
                    System.out.println("Seeding database with test accounts...");

                    Account account1 = Account.builder()
                            .customerId("11111")
                            .balance(new BigDecimal("1000.00"))
                            .currency("TRY")
                            .status(AccountStatus.ACTIVE)
                            .dailyLimit(new BigDecimal("5000.00"))
                            .build();

                    Account account2 = Account.builder()
                            .customerId("22222")
                            .balance(new BigDecimal("500.00"))
                            .currency("USD")
                            .status(AccountStatus.ACTIVE)
                            .dailyLimit(new BigDecimal("10000.00"))
                            .build();

                    return accountRepository.saveAll(Arrays.asList(account1, account2));
                })
                .subscribe(
                        account -> log.info("Account created: {}", account.getId()),
                        error -> log.error("Error seeding data", error)
                );
    }
}
