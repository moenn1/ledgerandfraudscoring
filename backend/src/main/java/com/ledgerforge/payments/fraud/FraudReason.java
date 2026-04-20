package com.ledgerforge.payments.fraud;

public record FraudReason(
        String code,
        String message,
        int weight
) {
}
