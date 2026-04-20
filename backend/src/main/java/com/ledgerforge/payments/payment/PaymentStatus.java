package com.ledgerforge.payments.payment;

public enum PaymentStatus {
    CREATED,
    VALIDATED,
    RISK_SCORING,
    APPROVED,
    RESERVED,
    CAPTURED,
    SETTLED,
    REJECTED,
    REVERSED,
    CHARGEBACK,
    REFUNDED,
    CANCELLED
}
