package com.minelsaygisever.account.listener;

import com.minelsaygisever.account.dto.event.credit.TransferDepositRequestedEvent;
import com.minelsaygisever.account.dto.event.debit.TransferInitiatedEvent;
import com.minelsaygisever.account.dto.event.refund.TransferRefundRequestedEvent;
import com.minelsaygisever.account.service.handler.TransferCreditHandler;
import com.minelsaygisever.account.service.handler.TransferDebitHandler;
import com.minelsaygisever.account.service.handler.TransferRefundHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransferEventsListener {

    private final TransferDebitHandler debitHandler;
    private final TransferCreditHandler creditHandler;
    private final TransferRefundHandler refundHandler;

    // --- DEBIT ---
    @Bean
    public Consumer<TransferInitiatedEvent> transferInitiated() {
        return event -> {
            log.info("EVENT RECEIVED: Transfer Initiated (Debit) for Tx: {}", event.transactionId());

            debitHandler.handle(event)
                    .doOnSuccess(v -> log.info("DEBIT SUCCESS: Tx: {}", event.transactionId()))
                    .doOnError(e -> log.error("DEBIT ERROR: Tx: {}", event.transactionId(), e))
                    .subscribe();
        };
    }

    // --- CREDIT ---
    @Bean
    public Consumer<TransferDepositRequestedEvent> transferDepositRequested() {
        return event -> {
            log.info("EVENT RECEIVED: Transfer Deposit (Credit) for Tx: {}", event.transactionId());

            creditHandler.handle(event)
                    .doOnSuccess(v -> log.info("CREDIT SUCCESS: Tx: {}", event.transactionId()))
                    .doOnError(e -> log.error("CREDIT ERROR: Tx: {}", event.transactionId(), e))
                    .subscribe();
        };
    }

    // --- REFUND ---
    @Bean
    public Consumer<TransferRefundRequestedEvent> transferRefundRequested() {
        return event -> {
            log.info("EVENT RECEIVED: Transfer Refund for Tx: {}", event.transactionId());

            refundHandler.handle(event)
                    .doOnSuccess(v -> log.info("REFUND SUCCESS: Tx: {}", event.transactionId()))
                    .doOnError(e -> log.error("REFUND ERROR: Tx: {}", event.transactionId(), e))
                    .subscribe();
        };
    }

}
