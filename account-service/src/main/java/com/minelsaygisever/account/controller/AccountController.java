package com.minelsaygisever.account.controller;

import com.minelsaygisever.account.controller.api.AccountApi;
import com.minelsaygisever.account.dto.AccountDto;
import com.minelsaygisever.account.dto.CreateAccountRequest;
import com.minelsaygisever.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
public class AccountController implements AccountApi {

    private final AccountService accountService;

    @Override
    public Mono<ResponseEntity<AccountDto>> create(CreateAccountRequest request) {
        return accountService.create(request)
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<AccountDto>> findById(String id) {
        return accountService.findById(id)
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Void>> addMoney(String id, BigDecimal amount, String currency) {
        return accountService.addMoney(id, amount, currency)
                .thenReturn(ResponseEntity.ok().build());
    }

    @Override
    public Mono<ResponseEntity<Void>> withdraw(String id, BigDecimal amount, String currency) {
        return accountService.withdraw(id, amount, currency)
                .thenReturn(ResponseEntity.ok().build());
    }
}
