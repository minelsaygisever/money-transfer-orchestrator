package com.minelsaygisever.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record TransferApiRequest(
        @Schema(description = "Sender Account ID", example = "1")
        @NotBlank(message = "Sender account ID is required")
        String senderAccountId,

        @Schema(description = "Receiver Account ID", example = "3")
        @NotBlank(message = "Receiver account ID is required")
        String receiverAccountId,

        @Schema(description = "Amount to transfer", example = "100.00")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Transfer amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "Amount must have max 2 decimal places")
        BigDecimal amount,

        @Schema(description = "Currency code", example = "TRY")
        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be exactly 3 characters (ISO 4217)")
        String currency
) {}