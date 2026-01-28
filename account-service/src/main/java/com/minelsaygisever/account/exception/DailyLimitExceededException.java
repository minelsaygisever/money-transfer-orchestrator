package com.minelsaygisever.account.exception;

import lombok.Getter;

@Getter
public class DailyLimitExceededException extends RuntimeException {
    private final String id;

    public DailyLimitExceededException(String id, String message) {
        super(message);
        this.id = id;
    }
}