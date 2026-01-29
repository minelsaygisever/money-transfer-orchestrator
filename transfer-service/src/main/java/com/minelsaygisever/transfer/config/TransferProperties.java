package com.minelsaygisever.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transfer")
public record TransferProperties(
        Duration lockTimeout
) {
    public TransferProperties {
        if (lockTimeout == null) {
            lockTimeout = Duration.ofMinutes(5);
        }
    }
}
