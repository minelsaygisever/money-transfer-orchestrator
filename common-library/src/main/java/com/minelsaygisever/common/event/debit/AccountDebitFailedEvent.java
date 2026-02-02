package com.minelsaygisever.common.event.debit;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDebitFailedEvent(
        UUID transactionId,
        String senderAccountId,
        BigDecimal amount,
        String currency,
        String reason
) {}