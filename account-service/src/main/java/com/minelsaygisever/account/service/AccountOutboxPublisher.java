package com.minelsaygisever.account.service;

import com.minelsaygisever.account.config.AccountProperties;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.exception.EventPublishingException;
import com.minelsaygisever.account.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountOutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final StreamBridge streamBridge;
    private final AccountProperties properties;
    private final TransactionalOperator transactionalOperator;

    @Scheduled(fixedDelayString = "${account.outbox.polling-interval:500}")
    public void pollOutbox() {
        processOutbox()
                .as(transactionalOperator::transactional)
                .subscribe(
                        outbox -> log.debug("Event published from Account Outbox. ID: {}", outbox.getId()),
                        error -> log.error("Error in Account Outbox polling", error)
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
                    log.info("Publishing Account Event. ID: {} Type: {}", outbox.getId(), outbox.getType());

                    Message<String> message = MessageBuilder
                            .withPayload(outbox.getPayload())
                            .setHeader("partitionKey", outbox.getAggregateId())
                            .setHeader("eventType", outbox.getType().name())
                            .build();

                    boolean sent = streamBridge.send(properties.outbox().bindingName(), message);

                    if (!sent) {
                        throw new EventPublishingException("StreamBridge failed to send event for Outbox ID: " + outbox.getId());
                    }
                    return true;
                })
                .flatMap(success -> handleSuccess(outbox))
                .onErrorResume(ex -> handleFailure(outbox, ex));
    }

    private Mono<Outbox> handleSuccess(Outbox outbox) {
        log.info("Event published successfully. Marking COMPLETED. ID: {}", outbox.getId());
        outbox.setStatus(OutboxStatus.COMPLETED);
        outbox.setNextAttemptTime(null);
        return outboxRepository.save(outbox);
    }

    private Mono<Outbox> handleFailure(Outbox outbox, Throwable ex) {
        log.error("Failed to publish Account Event. ID: {}", outbox.getId(), ex);

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
                throw new RuntimeException("Failed to send to DLQ");
            }
            return true;
        });
    }
}