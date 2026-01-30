package com.minelsaygisever.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.AggregateType;
import com.minelsaygisever.account.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.dto.event.AccountDebitFailedEvent;
import com.minelsaygisever.account.dto.event.AccountDebitedEvent;
import com.minelsaygisever.account.dto.event.TransferInitiatedEvent;
import com.minelsaygisever.account.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountTransferHandler {

    private final AccountService accountService;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Mono<Void> handleDebit(TransferInitiatedEvent event) {
        return accountService.withdraw(event.senderAccountId(), event.amount(), event.currency())
                .then(saveDebitSuccessEvent(event))
                .onErrorResume(ex -> {
                    log.warn("Debit failed for tx: {}. Reason: {}", event.transactionId(), ex.getMessage());
                    return saveDebitFailureEvent(event, ex.getMessage());
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
}