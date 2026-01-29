package com.minelsaygisever.transfer.exception;

import com.minelsaygisever.common.exception.CurrencyMismatchException;
import com.minelsaygisever.transfer.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(TransferProcessInProgressException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleInProgress(TransferProcessInProgressException ex, ServerWebExchange exchange) {
        log.warn("Duplicate request conflict: {}", ex.getMessage());

        return Mono.just(createErrorResponse(
                HttpStatus.CONFLICT,
                "TRANSFER_IN_PROGRESS",
                ex.getMessage(),
                exchange
        ));
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleCurrencyMismatch(CurrencyMismatchException ex, ServerWebExchange exchange) {
        return Mono.just(createErrorResponse(
                HttpStatus.CONFLICT,
                "CURRENCY_MISMATCH",
                ex.getMessage(),
                exchange
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleValidationException(ConstraintViolationException ex, ServerWebExchange exchange) {
        return Mono.just(createErrorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ex.getMessage(),
                exchange
        ));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleRequestBodyValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        String errors = ex.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));

        return Mono.just(createErrorResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                errors,
                exchange
        ));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGeneralException(Exception ex, ServerWebExchange exchange) {
        log.error("Unexpected error occurred: ", ex);

        return Mono.just(createErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred. Please contact support.",
                exchange
        ));
    }

    private ResponseEntity<ErrorResponse> createErrorResponse(HttpStatus status, String errorKey, String message, ServerWebExchange exchange) {
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                errorKey,
                message,
                exchange.getRequest().getPath().value()
        );
        return ResponseEntity.status(status).body(errorResponse);
    }
}