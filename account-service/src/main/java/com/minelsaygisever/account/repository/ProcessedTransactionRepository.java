package com.minelsaygisever.account.repository;

import com.minelsaygisever.account.domain.ProcessedTransaction;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface ProcessedTransactionRepository extends R2dbcRepository<ProcessedTransaction, UUID> {

    @Query("""
        INSERT INTO processed_transactions(transaction_id, operation_type)
        VALUES (:transactionId, :operationType)
        ON CONFLICT (transaction_id, operation_type) DO NOTHING
        RETURNING transaction_id
    """)
    Mono<UUID> tryInsert(UUID transactionId, String operationType);
}
