package com.minelsaygisever.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @Schema(description = "Customer ID", example = "12345")
        @NotBlank(message = "Customer ID cannot be empty")
        String customerId,

        @Schema(description = "Initial deposit amount", example = "1000.00")
        @NotNull(message = "Initial amount is required")
        @DecimalMin(value = "0.00", message = "Initial amount cannot be negative")
        BigDecimal initialAmount,

        @Schema(description = "Currency code (ISO 4217)", example = "TRY")
        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency code must be 3 characters")
        String currency
) {}
