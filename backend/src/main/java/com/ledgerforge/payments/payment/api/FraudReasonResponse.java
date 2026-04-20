package com.ledgerforge.payments.payment.api;

import com.ledgerforge.payments.fraud.FraudReason;

public record FraudReasonResponse(
        String code,
        String message,
        int weight
) {
    public static FraudReasonResponse from(FraudReason reason) {
        return new FraudReasonResponse(reason.code(), reason.message(), reason.weight());
    }
}
