package com.minelsaygisever.transfer.dto;

import java.math.BigDecimal;

public record TransferCommand(
        String idempotencyKey,
        String senderAccountId,
        String receiverAccountId,
        BigDecimal amount,
        String currency
) {}