package com.minelsaygisever.transfer.job;

import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupJob {

    private final OutboxRepository outboxRepository;
    private final TransferProperties properties;

    @Scheduled(cron = "${transfer.cleanup.cron:0 0 3 * * *}")
    public void cleanupOldEvents() {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.cleanup().retentionPeriod());
        int batchSize = properties.cleanup().batchSize();

        log.info("Starting Transfer Outbox Cleanup. Deleting COMPLETED events before: {}", threshold);

        deleteBatch(threshold, batchSize)
                .subscribe(
                        totalDeleted -> log.info("Transfer Outbox Cleanup Finished. Total deleted rows: {}", totalDeleted),
                        error -> log.error("Transfer Outbox Cleanup Failed", error)
                );
    }

    private Mono<Integer> deleteBatch(LocalDateTime threshold, int batchSize) {
        return outboxRepository.deleteByStatusAndCreatedAtBefore(OutboxStatus.COMPLETED, threshold, batchSize)
                .flatMap(deletedCount -> {
                    if (deletedCount > 0) {
                        log.debug("Deleted batch of {} rows...", deletedCount);
                        return deleteBatch(threshold, batchSize)
                                .map(nextCount -> deletedCount + nextCount);
                    } else {
                        return Mono.just(0);
                    }
                });
    }
}
