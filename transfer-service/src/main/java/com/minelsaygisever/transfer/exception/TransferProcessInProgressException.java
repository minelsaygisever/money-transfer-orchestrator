package com.minelsaygisever.transfer.exception;

import lombok.Getter;

@Getter
public class TransferProcessInProgressException extends RuntimeException {

    private final String idempotencyKey;

    public TransferProcessInProgressException(String idempotencyKey) {
        super("Transfer is currently being processed for key: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }
}