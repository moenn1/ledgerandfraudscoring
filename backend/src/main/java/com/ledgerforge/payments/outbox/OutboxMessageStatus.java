package com.ledgerforge.payments.outbox;

public enum OutboxMessageStatus {
    PENDING,
    PUBLISHED,
    DEAD_LETTER
}
