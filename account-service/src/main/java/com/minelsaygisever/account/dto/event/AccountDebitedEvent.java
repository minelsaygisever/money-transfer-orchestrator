package com.minelsaygisever.account.dto.event;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDebitedEvent(
        UUID transactionId,
        String senderAccountId,
        BigDecimal amount,
        String currency
) {}
