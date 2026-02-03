package com.minelsaygisever.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "transfer")
public record TransferProperties(

        @DefaultValue("5m")
        Duration lockTimeout,

        @DefaultValue
        OutboxProperties outbox,

        @DefaultValue
        BackoffProperties backoff,

        @DefaultValue
        BindingProperties bindings,

        @DefaultValue
        ReconciliationProperties reconciliation
) {

    public record OutboxProperties(
            @DefaultValue("10")
            Integer batchSize,

            @DefaultValue("transfer-dlq-0")
            String dlqBindingName,

            @DefaultValue("500ms")
            Duration pollingInterval,

            @DefaultValue("5")
            Integer maxRetries
    ) {}

    public record BackoffProperties(
            @DefaultValue("1m")
            Duration initialDelay,

            @DefaultValue("60m")
            Duration maxDelay,

            @DefaultValue("2.0")
            Double multiplier
    ) {}

    public record BindingProperties(
            @DefaultValue("transfer-debit-out-0")
            String debit,

            @DefaultValue("transfer-credit-out-0")
            String credit,

            @DefaultValue("transfer-refund-out-0")
            String refund
    ) {}

    public record ReconciliationProperties(
            @DefaultValue("1m")
            Duration rate,

            @DefaultValue("5m")
            Duration timeoutThreshold
    ) {}
}
