package com.minelsaygisever.transfer.service;

import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.dto.TransferResponse;
import com.minelsaygisever.transfer.exception.IdempotencyKeyReuseException;
import com.minelsaygisever.transfer.exception.TransferProcessInProgressException;
import com.minelsaygisever.transfer.repository.TransferRepository;
import com.minelsaygisever.transfer.util.IdempotencyHasher;
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
    private final TransferSagaOrchestrator sagaOrchestrator;

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
                        return handleDuplicateRequest(request);
                    }
                });
    }

    private Mono<TransferResponse> processNewTransfer(TransferCommand request, String lockKey) {
        // check if it exists in the database.
        String incomingHash = IdempotencyHasher.hash(request);

        return transferRepository.findByIdempotencyKey(request.idempotencyKey())
                .flatMap(existing -> validateAndMapExisting(existing, incomingHash))
                .switchIfEmpty(Mono.defer(() -> createTransfer(request, lockKey)));
    }

    private Mono<TransferResponse> createTransfer(TransferCommand request, String lockKey) {
        String normalizedCurrency = request.currency().toUpperCase();
        UUID transactionId = UUID.randomUUID();
        String requestHash = IdempotencyHasher.hash(request);

        Transfer transfer = Transfer.builder()
                .idempotencyKey(request.idempotencyKey())
                .requestHash(requestHash)
                .transactionId(transactionId)
                .senderAccountId(request.senderAccountId())
                .receiverAccountId(request.receiverAccountId())
                .amount(request.amount())
                .currency(normalizedCurrency)
                .state(TransferState.STARTED)
                .build();

        return sagaOrchestrator.initiateSaga(transfer)
                .map(this::mapToResponse)
                .doOnSuccess(t -> log.info("Saga initiated successfully: {}", t.transactionId()))
                .onErrorResume(e -> {
                    log.error("Saga initiation failed. Releasing lock.", e);
                    return redisTemplate.opsForValue().delete(lockKey)
                            .then(Mono.error(e));
                });
    }

    private Mono<TransferResponse> handleDuplicateRequest(TransferCommand request) {
        String incomingHash = IdempotencyHasher.hash(request);

        return transferRepository.findByIdempotencyKey(request.idempotencyKey())
                .flatMap(existing ->  validateAndMapExisting(existing, incomingHash))
                .switchIfEmpty(Mono.error(new TransferProcessInProgressException(request.idempotencyKey())));
    }

    private TransferResponse mapToResponse(Transfer transfer) {
        return new TransferResponse(
                transfer.getTransactionId(),
                transfer.getState(),
                transfer.getCreatedAt()
        );
    }

    private Mono<TransferResponse> validateAndMapExisting(Transfer existing, String incomingHash) {
        String storedHash = existing.getRequestHash();

        // same key + different payload (or corrupted row) => 409
        if (storedHash == null || !storedHash.equals(incomingHash)) {
            return Mono.error(new IdempotencyKeyReuseException(existing.getIdempotencyKey()));
        }
        return Mono.just(mapToResponse(existing));
    }

}
