package com.minelsaygisever.transfer.repository;

import com.minelsaygisever.transfer.domain.Outbox;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

@Repository
public interface OutboxRepository extends R2dbcRepository<Outbox, Long> {
    @Query("""
        SELECT * FROM outbox 
        WHERE status = :status 
        AND (next_attempt_time IS NULL OR next_attempt_time <= :now) 
        ORDER BY id ASC 
        LIMIT :batchSize 
        FOR UPDATE SKIP LOCKED
    """)
    Flux<Outbox> findLockedBatch(OutboxStatus status, LocalDateTime now, int batchSize);
}
