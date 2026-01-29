package com.minelsaygisever.transfer.controller.api;

import com.minelsaygisever.transfer.dto.ErrorResponse;
import com.minelsaygisever.transfer.dto.TransferApiRequest;
import com.minelsaygisever.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Mono;

@Validated
@Tag(name = "Transfer Management", description = "APIs for managing money transfers with idempotency support")
@RequestMapping("/api/v1/transfers")
public interface TransferApi {

    @Operation(
            summary = "Initiate a Money Transfer",
            description = "Starts a new money transfer process. This endpoint is idempotent; sending the same request with the same 'x-idempotency-key' will return the previous result or a conflict if processing is ongoing."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Transfer initiated successfully (or existing record returned)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = TransferResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request payload or missing header",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Conflict - Transfer is currently being processed (Idempotency Lock)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal Server Error",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping
    Mono<ResponseEntity<TransferResponse>> initiateTransfer(
            @Parameter(
                    description = "Unique key to ensure idempotency (e.g., UUID). Prevents duplicate processing.",
                    required = true,
                    example = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"
            )
            @RequestHeader(name = "x-idempotency-key") String idempotencyKey,

            @RequestBody @Valid TransferApiRequest requestPayload
    );
}
