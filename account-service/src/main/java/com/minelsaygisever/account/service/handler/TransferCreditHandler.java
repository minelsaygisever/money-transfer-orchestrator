package com.minelsaygisever.account.service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OperationType;
import com.minelsaygisever.account.dto.event.credit.AccountCreditFailedEvent;
import com.minelsaygisever.account.dto.event.credit.AccountCreditedEvent;
import com.minelsaygisever.account.dto.event.credit.TransferDepositRequestedEvent;
import com.minelsaygisever.account.repository.OutboxRepository;
import com.minelsaygisever.account.repository.ProcessedTransactionRepository;
import com.minelsaygisever.account.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransferCreditHandler extends BaseTransactionHandler {

    private final AccountService accountService;
    private final OutboxRepository outboxRepository;
    private final ProcessedTransactionRepository processedTransactionRepository;
    private final TransactionalOperator txOp;

    public TransferCreditHandler(ObjectMapper objectMapper, AccountService accountService, OutboxRepository outboxRepository, ProcessedTransactionRepository processedTransactionRepository, TransactionalOperator txOp) {
        super(objectMapper);
        this.accountService = accountService;
        this.outboxRepository = outboxRepository;
        this.processedTransactionRepository = processedTransactionRepository;
        this.txOp = txOp;
    }

    public Mono<Void> handle(TransferDepositRequestedEvent event) {
        return processedTransactionRepository.tryInsert(event.transactionId(), OperationType.CREDIT.name())
                .hasElement()
                .flatMap(inserted -> {
                    if (Boolean.TRUE.equals(inserted)) {
                        return process(event);
                    } else {
                        log.info("DUPLICATE CREDIT EVENT IGNORED: tx={}", event.transactionId());
                        return Mono.empty();
                    }
                })
                .as(txOp::transactional);
    }

    private Mono<Void> process(TransferDepositRequestedEvent event) {
        return accountService.addMoney(event.receiverAccountId(), event.amount(), event.currency())
                .then(saveSuccessEvent(event))
                .onErrorResume(ex -> {
                    log.error("Credit Failed: {}", ex.getMessage());
                    return saveFailureEvent(event, ex.getMessage());
                })
                .then();
    }

    private Mono<Outbox> saveSuccessEvent(TransferDepositRequestedEvent event) {
        return Mono.fromCallable(() -> {
            var successEvent = new AccountCreditedEvent(event.transactionId(), event.receiverAccountId(), event.amount(), event.currency());
            return buildOutbox(event.receiverAccountId(), EventType.ACCOUNT_CREDITED, successEvent);
        }).flatMap(outboxRepository::save);
    }

    private Mono<Outbox> saveFailureEvent(TransferDepositRequestedEvent event, String reason) {
        return Mono.fromCallable(() -> {
            var failEvent = new AccountCreditFailedEvent(event.transactionId(), event.receiverAccountId(), event.amount(), event.currency(), reason);
            return buildOutbox(event.receiverAccountId(), EventType.ACCOUNT_CREDIT_FAILED, failEvent);
        }).flatMap(outboxRepository::save);
    }
}
