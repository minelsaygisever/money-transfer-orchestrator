package com.minelsaygisever.transfer.repository;

import com.minelsaygisever.transfer.domain.Outbox;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface OutboxRepository extends R2dbcRepository<Outbox, Long> {
    Flux<Outbox> findByStatus(OutboxStatus status);
}
