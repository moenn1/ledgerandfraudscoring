package com.ledgerforge.payments.payment.api;

import java.math.BigDecimal;

public record RefundPaymentRequest(
        BigDecimal amount,
        Long amountCents,
        String reason
) {
}
