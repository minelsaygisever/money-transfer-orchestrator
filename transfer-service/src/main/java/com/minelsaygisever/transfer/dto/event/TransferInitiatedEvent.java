package com.minelsaygisever.transfer.dto.event;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferInitiatedEvent(
        UUID transactionId,
        String senderAccountId,
        String receiverAccountId,
        BigDecimal amount,
        String currency
) {}
