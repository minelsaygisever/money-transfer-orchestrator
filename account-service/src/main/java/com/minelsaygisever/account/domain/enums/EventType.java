package com.minelsaygisever.account.domain.enums;

public enum EventType {
    // --- DEBIT ---
    TRANSFER_INITIATED,         // Transfer Svc -> Account Svc
    ACCOUNT_DEBITED,
    ACCOUNT_DEBIT_FAILED,

    // --- CREDIT ---
    TRANSFER_DEPOSIT_REQUESTED, // Transfer Svc -> Account Svc
    ACCOUNT_CREDITED,
    ACCOUNT_CREDIT_FAILED,

    // --- REFUND ---
    TRANSFER_REFUND_REQUESTED,  // Transfer Svc -> Account Svc
    ACCOUNT_REFUNDED,
    ACCOUNT_REFUND_FAILED
}
