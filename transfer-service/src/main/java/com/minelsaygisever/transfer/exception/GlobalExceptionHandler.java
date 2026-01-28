package com.minelsaygisever.transfer.exception;

import com.minelsaygisever.transfer.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

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