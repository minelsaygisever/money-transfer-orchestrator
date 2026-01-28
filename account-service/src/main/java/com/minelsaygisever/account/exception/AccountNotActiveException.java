package com.minelsaygisever.account.exception;

import lombok.Getter;

@Getter
public class AccountNotActiveException extends RuntimeException {

    private final String id;

    public AccountNotActiveException(String id, String message) {
        super(message);
        this.id = id;
    }
}