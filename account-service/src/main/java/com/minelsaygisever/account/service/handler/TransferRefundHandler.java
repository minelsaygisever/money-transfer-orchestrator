package com.minelsaygisever.account.service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OperationType;
import com.minelsaygisever.common.event.refund.AccountRefundedEvent;
import com.minelsaygisever.common.event.refund.TransferRefundRequestedEvent;
import com.minelsaygisever.account.repository.OutboxRepository;
import com.minelsaygisever.account.repository.ProcessedTransactionRepository;
import com.minelsaygisever.account.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransferRefundHandler extends BaseTransactionHandler {

    private final AccountService accountService;
    private final OutboxRepository outboxRepository;
    private final ProcessedTransactionRepository processedTransactionRepository;
    private final TransactionalOperator txOp;

    public TransferRefundHandler(ObjectMapper objectMapper, AccountService accountService, OutboxRepository outboxRepository, ProcessedTransactionRepository processedTransactionRepository, TransactionalOperator txOp) {
        super(objectMapper);
        this.accountService = accountService;
        this.outboxRepository = outboxRepository;
        this.processedTransactionRepository = processedTransactionRepository;
        this.txOp = txOp;
    }

    public Mono<Void> handle(TransferRefundRequestedEvent event) {
        return processedTransactionRepository.tryInsert(event.transactionId(), OperationType.REFUND.name())
                .hasElement()
                .flatMap(inserted -> {
                    if (Boolean.TRUE.equals(inserted)) {
                        return process(event);
                    } else {
                        log.info("DUPLICATE REFUND EVENT IGNORED: tx={}", event.transactionId());
                        return Mono.empty();
                    }
                })
                .as(txOp::transactional);
    }

    private Mono<Void> process(TransferRefundRequestedEvent event) {
        return accountService.addMoney(event.senderAccountId(), event.amount(), event.currency())
                .then(saveSuccessEvent(event))
                .onErrorResume(ex -> {
                    log.error("CRITICAL: Refund Failed! Tx: {}", event.transactionId(), ex);
                    return Mono.error(ex);
                })
                .then();
    }

    private Mono<Outbox> saveSuccessEvent(TransferRefundRequestedEvent event) {
        return Mono.fromCallable(() -> {
            var successEvent = new AccountRefundedEvent(event.transactionId(), event.senderAccountId(), event.amount(), event.currency());
            return buildOutbox(event.senderAccountId(), EventType.ACCOUNT_REFUNDED, successEvent);
        }).flatMap(outboxRepository::save);
    }
}
