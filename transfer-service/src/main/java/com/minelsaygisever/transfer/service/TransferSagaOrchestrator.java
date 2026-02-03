package com.minelsaygisever.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.common.event.credit.AccountCreditFailedEvent;
import com.minelsaygisever.common.event.credit.AccountCreditedEvent;
import com.minelsaygisever.common.event.credit.TransferDepositRequestedEvent;
import com.minelsaygisever.common.event.debit.AccountDebitFailedEvent;
import com.minelsaygisever.common.event.debit.AccountDebitedEvent;
import com.minelsaygisever.common.event.debit.TransferInitiatedEvent;
import com.minelsaygisever.common.event.refund.AccountRefundFailedEvent;
import com.minelsaygisever.common.event.refund.AccountRefundedEvent;
import com.minelsaygisever.common.event.refund.TransferRefundRequestedEvent;
import com.minelsaygisever.transfer.domain.Outbox;
import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.enums.AggregateType;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferSagaOrchestrator {

    private final TransferRepository transferRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TransactionalOperator txOp;

    // --- STEP 0: START SAGA (Initial Save + Outbox) ---
    public Mono<Transfer> initiateSaga(Transfer transfer) {
        var event = new TransferInitiatedEvent(
                transfer.getTransactionId(),
                transfer.getSenderAccountId(),
                transfer.getReceiverAccountId(),
                transfer.getAmount(),
                transfer.getCurrency()
        );

        return transferRepository.save(transfer)
                .flatMap(savedTransfer ->
                        saveOutbox(savedTransfer.getTransactionId(), EventType.TRANSFER_INITIATED, event)
                                .thenReturn(savedTransfer)
                )
                .as(txOp::transactional);
    }

    // --- STEP 1: DEBIT SUCCESS -> TRIGGER CREDIT ---
    public Mono<Void> handleDebitSuccess(AccountDebitedEvent event) {
        return transferRepository.findByTransactionId(event.transactionId())
                .flatMap(transfer -> {
                    // Idempotency & State Check
                    if (transfer.getState() != TransferState.STARTED) {
                        log.warn("Debit success received but state is {}. Ignoring. Tx: {}", transfer.getState(), transfer.getId());
                        return Mono.empty();
                    }

                    log.info("Debit successful. Moving to DEBITED state and initiating DEPOSIT. Tx: {}", transfer.getId());
                    transfer.setState(TransferState.DEBITED);

                    // Create Next Command: DEPOSIT
                    var depositEvent = new TransferDepositRequestedEvent(
                            transfer.getTransactionId(),
                            transfer.getReceiverAccountId(),
                            transfer.getAmount(),
                            transfer.getCurrency()
                    );

                    return transferRepository.save(transfer)
                            .then(saveOutbox(transfer.getTransactionId(), EventType.TRANSFER_DEPOSIT_REQUESTED, depositEvent));
                })
                .as(txOp::transactional) // Atomicity: State Update + Outbox Insert
                .then();
    }

    // --- STEP 2: DEBIT FAIL -> ABORT ---
    public Mono<Void> handleDebitFail(AccountDebitFailedEvent event) {
        return transferRepository.findByTransactionId(event.transactionId())
                .flatMap(transfer -> {
                    if (transfer.getState() != TransferState.STARTED) {
                        return Mono.empty();
                    }

                    log.error("Debit failed. Marking transfer as DEBIT_FAILED. Tx: {}. Reason: {}", transfer.getId(), event.reason());
                    transfer.setState(TransferState.DEBIT_FAILED);

                    // No rollback needed because money was never taken.
                    return transferRepository.save(transfer);
                })
                .as(txOp::transactional)
                .then();
    }

    // --- STEP 3: CREDIT SUCCESS -> COMPLETE ---
    public Mono<Void> handleCreditSuccess(AccountCreditedEvent event) {
        return transferRepository.findByTransactionId(event.transactionId())
                .flatMap(transfer -> {
                    if (transfer.getState() == TransferState.COMPLETED) {
                        return Mono.empty(); // Already processed
                    }

                    if (transfer.getState() != TransferState.DEBITED) {
                        log.warn("Credit success received but state is {}. Possible race condition or timeout. Tx: {}", transfer.getState(), transfer.getId());
                        return Mono.empty();
                    }

                    log.info("Credit successful. SAGA COMPLETED successfully. Tx: {}", transfer.getId());
                    transfer.setState(TransferState.COMPLETED);

                    return transferRepository.save(transfer);
                })
                .as(txOp::transactional)
                .then();
    }

    // --- STEP 4: CREDIT FAIL -> TRIGGER REFUND (ROLLBACK) ---
    public Mono<Void> handleCreditFail(AccountCreditFailedEvent event) {
        return transferRepository.findByTransactionId(event.transactionId())
                .flatMap(transfer -> {
                    if (transfer.getState() != TransferState.DEBITED) {
                        return Mono.empty();
                    }

                    log.error("Credit failed! Initiating COMPENSATING TRANSACTION (Refund). Tx: {}. Reason: {}", transfer.getId(), event.reason());
                    transfer.setState(TransferState.REFUND_INITIATED);

                    // Create Next Command: REFUND
                    var refundEvent = new TransferRefundRequestedEvent(
                            transfer.getTransactionId(),
                            transfer.getSenderAccountId(),
                            transfer.getAmount(),
                            transfer.getCurrency(),
                            "Rollback due to Credit Failure: " + event.reason()
                    );

                    return transferRepository.save(transfer)
                            .then(saveOutbox(transfer.getTransactionId(), EventType.TRANSFER_REFUND_REQUESTED, refundEvent));
                })
                .as(txOp::transactional)
                .then();
    }

    // --- STEP 4.5: TIMEOUT -> TRIGGER REFUND (AUTO-ROLLBACK) ---
    public Mono<Void> handleTimeout(Transfer transfer) {
        // Optimistic Locking & Race Condition Check
        if (transfer.getState() != TransferState.DEBITED) {
            log.info("Timeout handler triggered but state is {}. Skipping rollback. Tx: {}",
                    transfer.getState(), transfer.getId());
            return Mono.empty();
        }

        log.warn("Saga Timeout detected! Initiating COMPENSATING TRANSACTION (Refund). Tx: {}", transfer.getId());

        transfer.setState(TransferState.REFUND_INITIATED);
        transfer.setFailureReason("Saga Timeout: Receiver did not respond within threshold.");

        var refundEvent = new TransferRefundRequestedEvent(
                transfer.getTransactionId(),
                transfer.getSenderAccountId(),
                transfer.getAmount(),
                transfer.getCurrency(),
                "Rollback due to Saga Timeout"
        );

        return transferRepository.save(transfer)
                .flatMap(savedTransfer -> saveOutbox(
                        savedTransfer.getTransactionId(),
                        EventType.TRANSFER_REFUND_REQUESTED,
                        refundEvent)
                )
                .as(txOp::transactional)
                .then();
    }

    // --- STEP 5: REFUND SUCCESS -> FINISH WITH REFUNDED ---
    public Mono<Void> handleRefundSuccess(AccountRefundedEvent event) {
        return transferRepository.findByTransactionId(event.transactionId())
                .flatMap(transfer -> {
                    log.info("Refund successful. Transfer marked as REFUNDED. Tx: {}", transfer.getId());
                    transfer.setState(TransferState.REFUNDED);
                    return transferRepository.save(transfer);
                })
                .as(txOp::transactional)
                .then();
    }

    // --- STEP 6: REFUND FAIL -> PANIC MODE (MANUAL INTERVENTION) ---
    public Mono<Void> handleRefundFail(AccountRefundFailedEvent event) {
        return transferRepository.findByTransactionId(event.transactionId())
                .flatMap(transfer -> {
                    log.error("CRITICAL: Refund failed! Money is stuck. Tx: {}. Reason: {}", transfer.getId(), event.reason());
                    transfer.setState(TransferState.REFUND_FAILED);
                    return transferRepository.save(transfer);
                })
                .as(txOp::transactional)
                .then();
    }

    // --- HELPER: Save to Outbox ---
    private Mono<Outbox> saveOutbox(UUID aggregateId, EventType type, Object payload) {
        return Mono.fromCallable(() -> {
            try {
                return Outbox.builder()
                        .aggregateType(AggregateType.TRANSFER)
                        .aggregateId(aggregateId.toString())
                        .type(type)
                        .payload(objectMapper.writeValueAsString(payload))
                        .status(OutboxStatus.PENDING)
                        .retryCount(0)
                        .build();
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Error serializing outbox payload", e);
            }
        }).flatMap(outboxRepository::save);
    }
}
