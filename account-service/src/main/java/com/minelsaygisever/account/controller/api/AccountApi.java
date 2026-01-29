package com.minelsaygisever.account.controller.api;

import com.minelsaygisever.account.dto.AccountDto;
import com.minelsaygisever.account.dto.CreateAccountRequest;
import com.minelsaygisever.account.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Validated
@Tag(name = "Account Management", description = "APIs for creating accounts, adding money, and withdrawals.")
@RequestMapping("/api/v1/accounts")
public interface AccountApi {

    @Operation(summary = "Create a new account", description = "Creates a new bank account with initial balance and currency.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountDto.class))),
            @ApiResponse(responseCode = "500", description = "Internal Server Error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    Mono<ResponseEntity<AccountDto>> create(
            @RequestBody @Valid CreateAccountRequest request
    );


    @Operation(summary = "Get Account Details", description = "Retrieves account balance and details by ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Account details retrieved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountDto.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    Mono<ResponseEntity<AccountDto>> findById(
            @Parameter(description = "Account ID", example = "1")
            @PathVariable String id
    );


    @Operation(summary = "Deposit Money", description = "Adds funds to an existing account.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Money added successfully"),
            @ApiResponse(responseCode = "422", description = "Account not active or validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/add")
    Mono<ResponseEntity<Void>> addMoney(
            @Parameter(description = "Account ID", example = "1")
            @PathVariable String id,

            @RequestParam @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
            BigDecimal amount,

            @Parameter(description = "Currency code (ISO 4217)", example = "TRY")
            @RequestParam @NotBlank(message = "Currency is required")
            @Size(min = 3, max = 3, message = "Currency code must be 3 characters")
            String currency
    );


    @Operation(summary = "Withdraw Money", description = "Withdraws funds from an account with balance and limit checks.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Withdrawal successful"),
            @ApiResponse(responseCode = "422", description = "Insufficient funds, limit exceeded, or account inactive",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/withdraw")
    Mono<ResponseEntity<Void>> withdraw(
            @Parameter(description = "Account ID", example = "1")
            @PathVariable String id,

            @Parameter(description = "Amount to withdraw", example = "100.00")
            @RequestParam @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
            BigDecimal amount,

            @Parameter(description = "Currency code (ISO 4217)", example = "TRY")
            @RequestParam @NotBlank(message = "Currency is required")
            @Size(min = 3, max = 3, message = "Currency code must be 3 characters")
            String currency
    );
}
