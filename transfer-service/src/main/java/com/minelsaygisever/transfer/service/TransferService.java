package com.minelsaygisever.transfer.service;

import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.TransferState;
import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.dto.TransferResponse;
import com.minelsaygisever.transfer.exception.TransferProcessInProgressException;
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
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final TransferProperties properties;

    @Transactional
    public Mono<TransferResponse> initiateTransfer(TransferCommand request) {
        String lockKey = "transfer_lock:" + request.idempotencyKey();

        // Redis Atomic Lock (SETNX)
        return redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "LOCKED", properties.lockTimeout())
                .flatMap(acquired -> {
                    if (Boolean.TRUE.equals(acquired)) {
                        return processNewTransfer(request);
                    } else {
                        log.info("Duplicate request intercepted by Redis: {}", request.idempotencyKey());
                        return handleDuplicateRequest(request.idempotencyKey());
                    }
                });
    }

    private Mono<TransferResponse> processNewTransfer(TransferCommand request) {
        // check if it exists in the database.
        return transferRepository.findByIdempotencyKey(request.idempotencyKey())
                .flatMap(existing -> {
                    log.info("Idempotency hit in DB: {}", request.idempotencyKey());
                    return Mono.just(mapToResponse(existing));
                })
                .switchIfEmpty(Mono.defer(() -> createTransfer(request)));
    }

    private Mono<TransferResponse> createTransfer(TransferCommand request) {
        Transfer transfer = Transfer.builder()
                .idempotencyKey(request.idempotencyKey())
                .transactionId(UUID.randomUUID())
                .senderAccountId(request.senderAccountId())
                .receiverAccountId(request.receiverAccountId())
                .amount(request.amount())
                .currency(request.currency())
                .state(TransferState.STARTED)
                .build();

        return transferRepository.save(transfer)
                .map(this::mapToResponse)
                .doOnSuccess(t -> log.info("Transfer initiated: {}", t.transactionId()));
        // TODO: Outbox Pattern event
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
