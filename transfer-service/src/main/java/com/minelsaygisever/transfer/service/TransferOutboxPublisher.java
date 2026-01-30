package com.minelsaygisever.transfer.service;

import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.Outbox;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import com.minelsaygisever.transfer.exception.EventPublishingException;
import com.minelsaygisever.transfer.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferOutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final StreamBridge streamBridge;
    private final TransferProperties properties;
    private final TransactionalOperator transactionalOperator;

    @Scheduled(fixedDelayString = "${transfer.outbox-polling-interval:500}")
    public void pollOutbox() {
        processOutbox()
                .as(transactionalOperator::transactional)
                .subscribe(
                        result -> log.debug("Processed outbox ID: {}", result.getId()), // Success handler
                        error -> log.error("Error in outbox polling cycle", error)      // Error handler
                );
    }

    public Flux<Outbox> processOutbox() {
        return outboxRepository.findLockedBatch(
                        OutboxStatus.PENDING,
                        LocalDateTime.now(),
                        properties.outbox().batchSize()
                )
                .flatMap(this::publishEvent);
    }

    private Mono<Outbox> publishEvent(Outbox outbox) {
        return Mono.fromCallable(() -> {
                    log.info("Publishing event to Kafka. ID: {} Type: {}", outbox.getId(), outbox.getType());

                    Message<String> message = MessageBuilder
                            .withPayload(outbox.getPayload())
                            .setHeader("partitionKey", outbox.getAggregateId())
                            .build();

                    boolean sent = streamBridge.send(properties.outbox().bindingName(), message);

                    if (!sent) {
                        throw new EventPublishingException("StreamBridge failed to send event for Outbox ID: " + outbox.getId());
                    }
                    return true;
                })
                .flatMap(success -> handleSuccess(outbox))
                .onErrorResume(e -> handleFailure(outbox, e));
    }

    private Mono<Outbox> handleSuccess(Outbox outbox) {
        log.info("Event published successfully. Marking COMPLETED. ID: {}", outbox.getId());
        outbox.setStatus(OutboxStatus.COMPLETED);
        outbox.setNextAttemptTime(null);
        return outboxRepository.save(outbox);
    }

    private Mono<Outbox> handleFailure(Outbox outbox, Throwable ex) {
        log.error("Failed to publish event. ID: {}", outbox.getId(), ex);

        int currentRetryCount = outbox.getRetryCount() == null ? 0 : outbox.getRetryCount();
        int maxRetries = properties.outbox().maxRetries();

        if (currentRetryCount >= maxRetries) {
            log.warn("Max retries ({}) reached for ID: {}. Attempting to move to DLQ.", maxRetries, outbox.getId());

            return sendToDeadLetterQueue(outbox)
                    .flatMap(success -> {
                        outbox.setStatus(OutboxStatus.FAILED);
                        outbox.setNextAttemptTime(null);
                        log.info("Moved to DLQ and marked as FAILED. ID: {}", outbox.getId());
                        return outboxRepository.save(outbox);
                    })
                    .onErrorResume(dlqEx -> {
                        log.error("Failed to send to DLQ too! Marking as FAILED in DB only. ID: {}", outbox.getId(), dlqEx);
                        outbox.setStatus(OutboxStatus.FAILED);
                        outbox.setNextAttemptTime(null);
                        return outboxRepository.save(outbox);
                    });

        } else {
            int nextRetryCount = currentRetryCount + 1;
            outbox.setRetryCount(nextRetryCount);

            // Formula: Initial * (Multiplier ^ (Retry - 1))
            long delaySeconds = (long) (properties.backoff().initialDelay().toSeconds() * Math.pow(properties.backoff().multiplier(), currentRetryCount));
            long cappedDelaySeconds = Math.min(delaySeconds, properties.backoff().maxDelay().toSeconds());
            LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(cappedDelaySeconds);
            outbox.setNextAttemptTime(nextAttempt);

            log.info("Scheduled retry #{} for ID: {} in {} seconds.", nextRetryCount, outbox.getId(), cappedDelaySeconds);

            return outboxRepository.save(outbox);
        }
    }

    private Mono<Boolean> sendToDeadLetterQueue(Outbox outbox) {
        return Mono.fromCallable(() -> {
            log.info("Sending to DLQ Topic: {}", properties.outbox().dlqBindingName());
            boolean sent = streamBridge.send(properties.outbox().dlqBindingName(), outbox.getPayload());
            if (!sent) {
                throw new EventPublishingException("Failed to send to DLQ");
            }
            return true;
        });
    }
}
