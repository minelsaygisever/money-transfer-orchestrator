package com.minelsaygisever.transfer.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.common.event.credit.AccountCreditFailedEvent;
import com.minelsaygisever.common.event.credit.AccountCreditedEvent;
import com.minelsaygisever.common.event.debit.AccountDebitFailedEvent;
import com.minelsaygisever.common.event.debit.AccountDebitedEvent;
import com.minelsaygisever.common.event.refund.AccountRefundFailedEvent;
import com.minelsaygisever.common.event.refund.AccountRefundedEvent;
import com.minelsaygisever.transfer.exception.EventDeserializationException;
import com.minelsaygisever.transfer.service.TransferSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransferSagaListener {

    private final TransferSagaOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    @Bean
    public Consumer<Message<String>> onAccountEvent() {
        return message -> {
            String payload = message.getPayload();
            String eventTypeHeader = null;
            Object headerValue = message.getHeaders().get("eventType");

            if (headerValue instanceof byte[]) {
                eventTypeHeader = new String((byte[]) headerValue, StandardCharsets.UTF_8);
            } else if (headerValue instanceof String) {
                eventTypeHeader = (String) headerValue;
            }

            if (eventTypeHeader == null) {
                log.warn("Received message without 'eventType' header. Ignoring. Payload: {}", message.getPayload());
                return;
            }

            try {
                EventType eventType = EventType.valueOf(eventTypeHeader);

                log.info("Received Event: {} Payload: {}", eventType, payload);

                switch (eventType) {
                    case ACCOUNT_DEBITED -> {
                        var event = objectMapper.readValue(payload, AccountDebitedEvent.class);
                        orchestrator.handleDebitSuccess(event).block();
                    }
                    case ACCOUNT_DEBIT_FAILED -> {
                        var event = objectMapper.readValue(payload, AccountDebitFailedEvent.class);
                        orchestrator.handleDebitFail(event).block();
                    }
                    case ACCOUNT_CREDITED -> {
                        var event = objectMapper.readValue(payload, AccountCreditedEvent.class);
                        orchestrator.handleCreditSuccess(event).block();
                    }
                    case ACCOUNT_CREDIT_FAILED -> {
                        var event = objectMapper.readValue(payload, AccountCreditFailedEvent.class);
                        orchestrator.handleCreditFail(event).block();
                    }
                    case ACCOUNT_REFUNDED -> {
                        var event = objectMapper.readValue(payload, AccountRefundedEvent.class);
                        orchestrator.handleRefundSuccess(event).block();
                    }
                    case ACCOUNT_REFUND_FAILED -> {
                        var event = objectMapper.readValue(payload, AccountRefundFailedEvent.class);
                        orchestrator.handleRefundFail(event).block();
                    }
                    default -> log.debug("Ignored irrelevant event type for Transfer Saga: {}", eventType);
                }

            } catch (IllegalArgumentException e) {
                log.error("Unknown event type in header: {}", eventTypeHeader);
            } catch (IOException e) {
                log.error("Failed to deserialize event payload", e);
                throw new EventDeserializationException("Failed to deserialize event payload: " + payload, e);
            } catch (Exception e) {
                log.error("Error processing saga event", e);
                throw e;
            }
        };
    }
}