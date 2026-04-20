package com.ledgerforge.payments.payment.api;

import com.ledgerforge.payments.payment.PaymentIntentEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID payerAccountId,
        UUID payeeAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String idempotencyKey,
        Integer riskScore,
        String decision,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(PaymentIntentEntity payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPayerAccountId(),
                payment.getPayeeAccountId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getIdempotencyKey(),
                payment.getRiskScore(),
                payment.getRiskDecision() == null ? null : payment.getRiskDecision().name(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
