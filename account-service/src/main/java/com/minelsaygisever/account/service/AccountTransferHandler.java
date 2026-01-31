package com.minelsaygisever.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.AggregateType;
import com.minelsaygisever.account.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OperationType;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.dto.event.AccountDebitFailedEvent;
import com.minelsaygisever.account.dto.event.AccountDebitedEvent;
import com.minelsaygisever.account.dto.event.TransferInitiatedEvent;
import com.minelsaygisever.account.exception.AccountNotActiveException;
import com.minelsaygisever.account.exception.AccountNotFoundException;
import com.minelsaygisever.account.exception.DailyLimitExceededException;
import com.minelsaygisever.account.exception.InsufficientBalanceException;
import com.minelsaygisever.account.repository.OutboxRepository;
import com.minelsaygisever.account.repository.ProcessedTransactionRepository;
import com.minelsaygisever.common.exception.CurrencyMismatchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountTransferHandler {

    private final AccountService accountService;
    private final OutboxRepository outboxRepository;
    private final ProcessedTransactionRepository processedTransactionRepository;
    private final ObjectMapper objectMapper;
    private final TransactionalOperator txOp;

    public Mono<Void> handleDebit(TransferInitiatedEvent event) {
        return processedTransactionRepository.tryInsert(event.transactionId(), OperationType.DEBIT.name())
                .hasElement()
                .flatMap(inserted -> {
                    if (Boolean.TRUE.equals(inserted)) {
                        return processTransfer(event);
                    } else {
                        log.info("DUPLICATE DEBIT EVENT IGNORED: tx={}", event.transactionId());
                        return Mono.empty();
                    }
                })
                .as(txOp::transactional);
    }

    private Mono<Void> processTransfer(TransferInitiatedEvent event) {
        log.info("Processing NEW transfer transaction: {}", event.transactionId());

        return accountService.withdraw(
                        event.senderAccountId(),
                        event.amount(),
                        event.currency()
                )
                .then(saveDebitSuccessEvent(event))
                .onErrorResume(ex -> {
                    if (isBusinessError(ex)) {
                        log.warn("Business Validation Failed for tx: {}. Reason: {}", event.transactionId(), ex.getMessage());
                        return saveDebitFailureEvent(event, ex.getMessage());
                    }
                    return Mono.error(ex);
                })
                .then();
    }

    private Mono<Outbox> saveDebitSuccessEvent(TransferInitiatedEvent event) {
        return Mono.fromCallable(() -> {
            // Event: ACCOUNT_DEBITED
            AccountDebitedEvent successEvent = new AccountDebitedEvent(
                    event.transactionId(),
                    event.senderAccountId(),
                    event.amount(),
                    event.currency()
            );
            return buildOutbox(event.senderAccountId(), EventType.ACCOUNT_DEBITED, successEvent);
        }).flatMap(outboxRepository::save);
    }

    private Mono<Outbox> saveDebitFailureEvent(TransferInitiatedEvent event, String reason) {
        return Mono.fromCallable(() -> {
            // Event: ACCOUNT_DEBIT_FAILED
            AccountDebitFailedEvent failEvent = new AccountDebitFailedEvent(
                    event.transactionId(),
                    event.senderAccountId(),
                    event.amount(),
                    reason
            );
            return buildOutbox(event.senderAccountId(), EventType.ACCOUNT_DEBIT_FAILED, failEvent);
        }).flatMap(outboxRepository::save);
    }

    private Outbox buildOutbox(String aggregateId, EventType type, Object payloadObj) {
        try {
            return Outbox.builder()
                    .aggregateType(AggregateType.ACCOUNT)
                    .aggregateId(aggregateId)
                    .type(type)
                    .payload(objectMapper.writeValueAsString(payloadObj))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization error", e);
        }
    }

    private boolean isBusinessError(Throwable ex) {
        return ex instanceof InsufficientBalanceException ||
                ex instanceof AccountNotFoundException ||
                ex instanceof AccountNotActiveException ||
                ex instanceof DailyLimitExceededException ||
                ex instanceof CurrencyMismatchException;
    }
}