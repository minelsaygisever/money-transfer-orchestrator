package com.minelsaygisever.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transfer")
public record TransferProperties(
        Duration lockTimeout,
        Integer outboxBatchSize,
        String outboxBindingName,
        String outboxDlqBindingName,
        Long outboxPollingInterval,
        Integer outboxMaxRetries,
        Duration backoffInitialDelay,
        Duration backoffMaxDelay,
        Double backoffMultiplier
) {
    public TransferProperties {
        if (lockTimeout == null) {
            lockTimeout = Duration.ofMinutes(5);
        }

        if (outboxBatchSize == null) {
            outboxBatchSize = 10;
        }

        if (outboxBindingName == null) {
            outboxBindingName = "transfer-out-0";
        }

        if (outboxDlqBindingName == null) {
            outboxDlqBindingName = "transfer-dlq-0";
        }

        if (outboxPollingInterval == null) {
            outboxPollingInterval = 500L;
        }

        if (outboxMaxRetries == null) {
            outboxMaxRetries = 5;
        }

        if (backoffInitialDelay == null) {
            backoffInitialDelay = Duration.ofMinutes(1);
        }

        if (backoffMaxDelay == null) {
            backoffMaxDelay = Duration.ofHours(1);
        }

        if (backoffMultiplier == null) {
            backoffMultiplier = 2.0;
        }
    }
}
