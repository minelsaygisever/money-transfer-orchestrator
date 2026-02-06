package com.minelsaygisever.account.integration;

import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.AggregateType;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.job.OutboxCleanupJob;
import com.minelsaygisever.account.repository.OutboxRepository;
import com.minelsaygisever.common.domain.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@Import(TestChannelBinderConfiguration.class)
class AccountCleanupTest  {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("schema.sql");

    @Autowired
    private OutboxCleanupJob cleanupJob;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll().block();
    }

    @Test
    void shouldCleanOldCompletedEvents_AndKeepRecentOrPendingOnes() {
        // 1. Arrange
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

        // 3. Assert: Only A
        await().untilAsserted(() -> {
            var allOutbox = outboxRepository.findAll().collectList().block();

            assertThat(allOutbox).hasSize(2);
            assertThat(allOutbox).extracting(Outbox::getId)
                    .doesNotContain(oldCompleted.getId())
                    .contains(oldPending.getId())
                    .contains(newCompleted.getId());
        });
    }

    private Outbox createOutbox(OutboxStatus status) {
        return Outbox.builder()
                .aggregateType(AggregateType.ACCOUNT)
                .aggregateId("acc-cleanup-test")
                .type(EventType.ACCOUNT_DEBITED)
                .payload("{}")
                .status(status)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void saveAndForceDate(Outbox outbox, LocalDateTime date) {
        Outbox saved = outboxRepository.save(outbox).block();

        Objects.requireNonNull(saved, "Saved entity cannot be null");

        databaseClient.sql("UPDATE outbox SET created_at = $1 WHERE id = $2")
                .bind(0, date)
                .bind(1, saved.getId())
                .fetch()
                .rowsUpdated()
                .block();
    }
}
