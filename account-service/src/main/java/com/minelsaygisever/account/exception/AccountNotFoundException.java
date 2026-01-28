package com.minelsaygisever.account.exception;

import lombok.Getter;

@Getter
public class AccountNotFoundException extends RuntimeException {
    private final String id;

    public AccountNotFoundException(String id, String message) {
        super(message);
        this.id = id;
    }
}