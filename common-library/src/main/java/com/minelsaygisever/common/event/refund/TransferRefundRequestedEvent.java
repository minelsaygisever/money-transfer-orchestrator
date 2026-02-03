package com.minelsaygisever.common.event.refund;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRefundRequestedEvent(
        UUID transactionId,
        String senderAccountId,
        BigDecimal amount,
        String currency,
        String reason
) {}
