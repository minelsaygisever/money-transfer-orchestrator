package com.minelsaygisever.common.event.debit;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDebitedEvent(
        UUID transactionId,
        String senderAccountId,
        BigDecimal amount,
        String currency
) {}
