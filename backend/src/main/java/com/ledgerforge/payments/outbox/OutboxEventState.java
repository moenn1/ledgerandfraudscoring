package com.ledgerforge.payments.outbox;

public enum OutboxEventState {
    PENDING,
    PUBLISHED,
    DEAD_LETTER
}
