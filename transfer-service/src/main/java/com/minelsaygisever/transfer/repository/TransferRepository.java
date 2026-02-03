package com.minelsaygisever.transfer.repository;

import com.minelsaygisever.transfer.domain.Transfer;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface TransferRepository extends R2dbcRepository<Transfer, Long> {
    Mono<Transfer> findByIdempotencyKey(String idempotencyKey);
    Mono<Transfer> findByTransactionId(UUID transactionId);
}
