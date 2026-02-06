package com.minelsaygisever.transfer.integration;

import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.transfer.domain.Outbox;
import com.minelsaygisever.transfer.domain.enums.AggregateType;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import com.minelsaygisever.transfer.job.OutboxCleanupJob;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.LocalDateTime;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class TransferCleanupTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxCleanupJob cleanupJob;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll().block();
        redisTemplate.execute(conn -> conn.serverCommands().flushAll()).blockLast();
    }

    @Test
    void shouldCleanOldCompletedEvents_AndKeepRecentOrPendingOnes() {
        // 1. Arrange: Data Setup
        LocalDateTime oldDate = LocalDateTime.now().minusDays(5);
        LocalDateTime newDate = LocalDateTime.now().minusDays(1);

        // A) OLD + COMPLETED
        Outbox oldCompleted = createOutbox(OutboxStatus.COMPLETED);
        saveAndForceDate(oldCompleted, oldDate);

        // B) OLD + PENDING
        Outbox oldPending = createOutbox(OutboxStatus.PENDING);
        saveAndForceDate(oldPending, oldDate);

        // C) NEW + COMPLETED
        Outbox newCompleted = createOutbox(OutboxStatus.COMPLETED);
        saveAndForceDate(newCompleted, newDate);

        // 2. Act
        cleanupJob.cleanupOldEvents();

        // 3. Assert
        await().untilAsserted(() -> {
            var all = outboxRepository.findAll().collectList().block();
            assertThat(all).hasSize(2); // Only A

            assertThat(all).extracting(Outbox::getId)
                    .doesNotContain(oldCompleted.getId())
                    .contains(oldPending.getId())
                    .contains(newCompleted.getId());
        });
    }

    private Outbox createOutbox(OutboxStatus status) {
        return Outbox.builder()
                .aggregateType(AggregateType.TRANSFER)
                .aggregateId("cleanup-test")
                .type(EventType.TRANSFER_INITIATED)
                .payload("{}")
                .status(status)
                .createdAt(LocalDateTime.now()) // Dummy
                .build();
    }

    private void saveAndForceDate(Outbox outbox, LocalDateTime date) {
        Outbox saved = outboxRepository.save(outbox).block();
        Objects.requireNonNull(saved, "Saved outbox entity cannot be null");
        databaseClient.sql("UPDATE outbox SET created_at = $1 WHERE id = $2")
                .bind(0, date)
                .bind(1, saved.getId())
                .fetch()
                .rowsUpdated()
                .block();
    }
}
