package com.minelsaygisever.account.dto;

import com.minelsaygisever.account.domain.AccountStatus;

import java.math.BigDecimal;

public record AccountDto(
        String id,
        String customerId,
        BigDecimal balance,
        String currency,
        AccountStatus status,
        BigDecimal dailyLimit
) {}
