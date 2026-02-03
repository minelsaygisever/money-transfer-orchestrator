package com.minelsaygisever.account.service.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.account.domain.Outbox;
import com.minelsaygisever.account.domain.enums.AggregateType;
import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import com.minelsaygisever.account.exception.AccountNotActiveException;
import com.minelsaygisever.account.exception.AccountNotFoundException;
import com.minelsaygisever.account.exception.DailyLimitExceededException;
import com.minelsaygisever.account.exception.InsufficientBalanceException;
import com.minelsaygisever.common.exception.CurrencyMismatchException;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class BaseTransactionHandler {

    protected final ObjectMapper objectMapper;

    protected boolean isBusinessError(Throwable ex) {
        return ex instanceof InsufficientBalanceException ||
                ex instanceof AccountNotFoundException ||
                ex instanceof AccountNotActiveException ||
                ex instanceof DailyLimitExceededException ||
                ex instanceof CurrencyMismatchException;
    }

    protected Outbox buildOutbox(String aggregateId, EventType type, Object payloadObj) {
        try {
            return Outbox.builder()
                    .aggregateType(AggregateType.ACCOUNT)
                    .aggregateId(aggregateId)
                    .type(type)
                    .payload(objectMapper.writeValueAsString(payloadObj))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization error", e);
        }
    }
}