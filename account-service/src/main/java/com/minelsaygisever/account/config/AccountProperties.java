package com.minelsaygisever.account.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "account")
@Validated
public record AccountProperties(

        @NotNull
        @DecimalMin(value = "0.00", message = "Default daily limit cannot be negative")
        BigDecimal defaultDailyLimit,

        @NotNull
        String defaultCurrency,

        @DefaultValue
        OutboxProperties outbox,

        @DefaultValue
        CleanupProperties cleanup,

        @DefaultValue
        BackoffProperties backoff
) {
        public record OutboxProperties(

                @DefaultValue("10")
                Integer batchSize,

                @DefaultValue("account-out-0")
                String bindingName,

                @DefaultValue("account-dlq-0")
                String dlqBindingName,

                @DefaultValue("500ms")
                Duration pollingInterval,

                @DefaultValue("5")
                Integer maxRetries
        ) {}

        public record CleanupProperties(
                @DefaultValue("0 0 3 * * *")
                String cron,

                @DefaultValue("3d")
                Duration retentionPeriod,

                @DefaultValue("500")
                Integer batchSize
        ) {}

        public record BackoffProperties(
                @DefaultValue("1m")
                Duration initialDelay,

                @DefaultValue("60m")
                Duration maxDelay,

                @DefaultValue("2.0")
                Double multiplier
        ) {}
}
