package com.minelsaygisever.account.exception;

import com.minelsaygisever.account.dto.ErrorResponse;
import com.minelsaygisever.common.exception.CurrencyMismatchException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleAccountNotFound(AccountNotFoundException ex, ServerWebExchange exchange) {
        return Mono.just(createErrorResponse(
                HttpStatus.NOT_FOUND,
                "ACCOUNT_NOT_FOUND",
                ex.getMessage(),
                exchange
        ));
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleAccountNotActive(AccountNotActiveException ex, ServerWebExchange exchange) {
        return Mono.just(createErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "ACCOUNT_NOT_ACTIVE",
                ex.getMessage(),
                exchange
        ));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGeneralException(Exception ex, ServerWebExchange exchange) {
        return Mono.just(createErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "Unexpected error occurred: " + ex.getMessage(),
                exchange
        ));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleInsufficientBalance(InsufficientBalanceException ex, ServerWebExchange exchange) {
        return Mono.just(createErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INSUFFICIENT_FUNDS",
                ex.getMessage(),
                exchange
        ));
    }

    @ExceptionHandler(DailyLimitExceededException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleDailyLimitExceeded(DailyLimitExceededException ex, ServerWebExchange exchange) {
        return Mono.just(createErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "DAILY_LIMIT_EXCEEDED",
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