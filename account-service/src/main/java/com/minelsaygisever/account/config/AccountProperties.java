package com.minelsaygisever.account.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "banking.account")
@Validated
public record AccountProperties(

        @NotNull
        @DecimalMin(value = "0.00", message = "Default daily limit cannot be negative")
        BigDecimal defaultDailyLimit,

        @NotNull
        String defaultCurrency
) {}
