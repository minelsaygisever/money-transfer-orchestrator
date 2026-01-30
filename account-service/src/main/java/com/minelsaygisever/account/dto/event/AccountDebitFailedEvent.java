package com.minelsaygisever.account.dto.event;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDebitFailedEvent(
        UUID transactionId,
        String senderAccountId,
        BigDecimal amount,
        String reason
) {}