package com.minelsaygisever.account.job;

import com.minelsaygisever.account.config.AccountProperties;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.repository.OutboxRepository;
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
    private final AccountProperties properties;

    @Scheduled(cron = "${account.cleanup.cron:0 0 3 * * *}")
    public void cleanupOldEvents() {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.cleanup().retentionPeriod());
        int batchSize = properties.cleanup().batchSize();

        log.info("Starting Outbox Cleanup. Deleting COMPLETED events before: {}", threshold);

        deleteBatch(threshold, batchSize)
                .subscribe(
                        totalDeleted -> log.info("Outbox Cleanup Finished. Total deleted rows: {}", totalDeleted),
                        error -> log.error("Outbox Cleanup Failed", error)
                );
    }

    private Mono<Integer> deleteBatch(LocalDateTime threshold, int batchSize) {
        return outboxRepository.deleteByStatusAndCreatedAtBefore(OutboxStatus.COMPLETED, threshold, batchSize)
                .flatMap(deletedCount -> {
                    if (deletedCount > 0) {
                        log.debug("Deleted {} rows. Continuing...", deletedCount);
                        // Recursive Loop
                        return deleteBatch(threshold, batchSize)
                                .map(nextCount -> deletedCount + nextCount);
                    } else {
                        return Mono.just(0);
                    }
                });
    }
}
