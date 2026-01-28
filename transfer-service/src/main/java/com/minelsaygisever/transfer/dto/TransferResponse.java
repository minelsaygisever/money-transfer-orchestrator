package com.minelsaygisever.transfer.dto;

import com.minelsaygisever.transfer.domain.TransferState;

import java.time.LocalDateTime;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        TransferState state,
        LocalDateTime createdAt
) {}