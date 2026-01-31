package com.minelsaygisever.account.dto.event.credit;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountCreditedEvent(
        UUID transactionId,
        String receiverAccountId,
        BigDecimal amount,
        String currency
) {}
