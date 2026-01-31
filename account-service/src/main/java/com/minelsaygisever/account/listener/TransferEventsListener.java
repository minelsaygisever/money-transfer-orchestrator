package com.minelsaygisever.account.listener;

import com.minelsaygisever.account.dto.event.TransferInitiatedEvent;
import com.minelsaygisever.account.service.AccountTransferHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransferEventsListener {

    private final AccountTransferHandler accountTransferHandler;

    @Bean
    public Consumer<TransferInitiatedEvent> processTransferInit() {
        return event -> {
            log.info("EVENT RECEIVED: Transfer Initiated for Transaction ID: {}", event.transactionId());

            accountTransferHandler.handleDebit(event)
                    .doOnSuccess(v -> log.info("STREAM SUCCESS: Transfer handled successfully for tx: {}", event.transactionId()))
                    .doOnError(e -> log.error("STREAM ERROR: Error handling transfer for tx: {}", event.transactionId(), e))
                    .subscribe();
        };
    }

}
