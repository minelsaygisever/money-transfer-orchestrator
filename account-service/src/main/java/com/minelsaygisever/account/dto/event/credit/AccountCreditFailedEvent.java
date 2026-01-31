package com.minelsaygisever.account.dto.event.credit;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountCreditFailedEvent(
        UUID transactionId,
        String receiverAccountId,
        BigDecimal amount,
        String currency,
        String reason
) {}
