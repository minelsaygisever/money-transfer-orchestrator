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
        BackoffProperties backoff
) {

    public record OutboxProperties(
            @DefaultValue("10")
            Integer batchSize,

            @DefaultValue("transfer-out-0")
            String bindingName,

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
}
