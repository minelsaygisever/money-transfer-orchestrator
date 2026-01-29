package com.minelsaygisever.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.Outbox;
import com.minelsaygisever.transfer.domain.enums.AggregateType;
import com.minelsaygisever.transfer.domain.enums.EventType;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.dto.TransferResponse;
import com.minelsaygisever.transfer.dto.event.TransferInitiatedEvent;
import com.minelsaygisever.transfer.exception.EventSerializationException;
import com.minelsaygisever.transfer.exception.TransferProcessInProgressException;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import com.minelsaygisever.transfer.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private final TransferRepository transferRepository;
    private final OutboxRepository outboxRepository;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final TransferProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public Mono<TransferResponse> initiateTransfer(TransferCommand request) {
        String lockKey = "transfer_lock:" + request.idempotencyKey();

        // Redis Atomic Lock (SETNX)
        return redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "LOCKED", properties.lockTimeout())
                .flatMap(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        return processNewTransfer(request, lockKey);
                    } else {
                        log.info("Duplicate request intercepted by Redis: {}", request.idempotencyKey());
                        return handleDuplicateRequest(request.idempotencyKey());
                    }
                });
    }

    private Mono<TransferResponse> processNewTransfer(TransferCommand request, String lockKey) {
        // check if it exists in the database.
        return transferRepository.findByIdempotencyKey(request.idempotencyKey())
                .flatMap(existing -> {
                    log.info("Idempotency hit in DB: {}", request.idempotencyKey());
                    return Mono.just(mapToResponse(existing));
                })
                .switchIfEmpty(Mono.defer(() -> createTransfer(request, lockKey)));
    }

    private Mono<TransferResponse> createTransfer(TransferCommand request, String lockKey) {
        String normalizedCurrency = request.currency().toUpperCase();
        UUID transactionId = UUID.randomUUID();

        Transfer transfer = Transfer.builder()
                .idempotencyKey(request.idempotencyKey())
                .transactionId(transactionId)
                .senderAccountId(request.senderAccountId())
                .receiverAccountId(request.receiverAccountId())
                .amount(request.amount())
                .currency(normalizedCurrency)
                .state(TransferState.STARTED)
                .build();

        Outbox outbox = prepareOutboxEvent(transfer);

        return transferRepository.save(transfer)
                .flatMap(savedTransfer -> {
                    log.info("Transfer saved. Saving to outbox... TxID: {}", savedTransfer.getTransactionId());
                    return outboxRepository.save(outbox)
                            .thenReturn(savedTransfer);
                })
                .map(this::mapToResponse)
                .doOnSuccess(t -> log.info("Transaction & Outbox committed successfully: {}", t.transactionId()))
                .onErrorResume(e -> {
                    log.error("DB Transaction failed. Releasing lock.", e);
                    return redisTemplate.opsForValue().delete(lockKey)
                            .then(Mono.error(e));
                });
    }

    private Outbox prepareOutboxEvent(Transfer transfer) {
        try {
            TransferInitiatedEvent event = new TransferInitiatedEvent(
                    transfer.getTransactionId(),
                    transfer.getSenderAccountId(),
                    transfer.getReceiverAccountId(),
                    transfer.getAmount(),
                    transfer.getCurrency()
            );

            String payload = objectMapper.writeValueAsString(event);

            return Outbox.builder()
                    .aggregateType(AggregateType.TRANSFER)
                    .aggregateId(transfer.getTransactionId().toString())
                    .type(EventType.TRANSFER_INITIATED)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();

        } catch (JsonProcessingException e) {
            throw new EventSerializationException("Could not serialize event for transaction: " + transfer.getTransactionId(), e);
        }
    }

    private Mono<TransferResponse> handleDuplicateRequest(String idempotencyKey) {
        return transferRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::mapToResponse)
                .switchIfEmpty(Mono.error(new TransferProcessInProgressException(idempotencyKey)));
    }

    private TransferResponse mapToResponse(Transfer transfer) {
        return new TransferResponse(
                transfer.getTransactionId(),
                transfer.getState(),
                transfer.getCreatedAt()
        );
    }
}
