package com.minelsaygisever.account.dto.event.debit;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferInitiatedEvent(
        UUID transactionId,
        String senderAccountId,
        String receiverAccountId,
        BigDecimal amount,
        String currency
) {}