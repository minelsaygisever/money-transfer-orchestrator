package com.minelsaygisever.transfer.repository;

import com.minelsaygisever.transfer.domain.Transfer;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface TransferRepository extends R2dbcRepository<Transfer, Long> {
    Mono<Transfer> findByIdempotencyKey(String idempotencyKey);
    Mono<Transfer> findByTransactionId(UUID transactionId);
    Flux<Transfer> findByStateAndUpdatedAtBefore(TransferState state, LocalDateTime threshold);
}
