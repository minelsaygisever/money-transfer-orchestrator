package com.minelsaygisever.account.exception;

import lombok.Getter;

@Getter
public class InsufficientBalanceException extends RuntimeException {
    private final String id;

    public InsufficientBalanceException(String id, String message) {
        super(message);
        this.id = id;
    }
}