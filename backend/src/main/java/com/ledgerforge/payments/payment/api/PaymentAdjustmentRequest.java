package com.ledgerforge.payments.payment.api;

import java.math.BigDecimal;

public record PaymentAdjustmentRequest(
        BigDecimal amount,
        Long amountCents,
        String reason
) {
}
