package com.minelsaygisever.transfer.domain.enums;

public enum TransferState {
    STARTED,

    DEBITED,
    DEBIT_FAILED,

    DEPOSIT_INITIATED,
    COMPLETED,

    DEPOSIT_FAILED,

    REFUND_INITIATED,
    REFUNDED,
    REFUND_FAILED
}
