package com.minelsaygisever.account.service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OperationType;
import com.minelsaygisever.account.dto.event.debit.AccountDebitFailedEvent;
import com.minelsaygisever.account.dto.event.debit.AccountDebitedEvent;
import com.minelsaygisever.account.dto.event.debit.TransferInitiatedEvent;
import com.minelsaygisever.account.repository.OutboxRepository;
import com.minelsaygisever.account.repository.ProcessedTransactionRepository;
import com.minelsaygisever.account.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransferDebitHandler extends BaseTransactionHandler {

    private final AccountService accountService;
    private final OutboxRepository outboxRepository;
    private final ProcessedTransactionRepository processedTransactionRepository;
    private final TransactionalOperator txOp;

    public TransferDebitHandler(ObjectMapper objectMapper,
                                AccountService accountService,
                                OutboxRepository outboxRepository,
                                ProcessedTransactionRepository processedTransactionRepository,
                                TransactionalOperator txOp) {
        super(objectMapper);
        this.accountService = accountService;
        this.outboxRepository = outboxRepository;
        this.processedTransactionRepository = processedTransactionRepository;
        this.txOp = txOp;
    }

    public Mono<Void> handle(TransferInitiatedEvent event) {
        return processedTransactionRepository.tryInsert(event.transactionId(), OperationType.DEBIT.name())
                .hasElement()
                .flatMap(inserted -> {
                    if (Boolean.TRUE.equals(inserted)) {
                        return process(event);
                    } else {
                        log.info("DUPLICATE DEBIT EVENT IGNORED: tx={}", event.transactionId());
                        return Mono.empty();
                    }
                })
                .as(txOp::transactional);
    }

    private Mono<Void> process(TransferInitiatedEvent event) {
        return accountService.withdraw(event.senderAccountId(), event.amount(), event.currency())
                .then(saveSuccessEvent(event))
                .onErrorResume(ex -> {
                    if (isBusinessError(ex)) {
                        log.warn("Debit Failed: {}", ex.getMessage());
                        return saveFailureEvent(event, ex.getMessage());
                    }
                    return Mono.error(ex);
                })
                .then();
    }

    private Mono<Outbox> saveSuccessEvent(TransferInitiatedEvent event) {
        return Mono.fromCallable(() -> {
            var successEvent = new AccountDebitedEvent(event.transactionId(), event.senderAccountId(), event.amount(), event.currency());
            return buildOutbox(event.senderAccountId(), EventType.ACCOUNT_DEBITED, successEvent);
        }).flatMap(outboxRepository::save);
    }

    private Mono<Outbox> saveFailureEvent(TransferInitiatedEvent event, String reason) {
        return Mono.fromCallable(() -> {
            var failEvent = new AccountDebitFailedEvent(event.transactionId(), event.senderAccountId(), event.amount(), event.currency(), reason);
            return buildOutbox(event.senderAccountId(), EventType.ACCOUNT_DEBIT_FAILED, failEvent);
        }).flatMap(outboxRepository::save);
    }
}
