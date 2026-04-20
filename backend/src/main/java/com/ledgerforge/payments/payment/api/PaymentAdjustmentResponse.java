package com.ledgerforge.payments.payment.api;

import com.ledgerforge.payments.common.security.SensitiveDataMasking;
import com.ledgerforge.payments.payment.PaymentAdjustmentEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentAdjustmentResponse(
        UUID id,
        String type,
        BigDecimal amount,
        BigDecimal feeAmount,
        String currency,
        String reason,
        UUID journalId,
        String correlationId,
        String idempotencyKey,
        Instant createdAt
) {
    public static PaymentAdjustmentResponse from(PaymentAdjustmentEntity adjustment) {
        return new PaymentAdjustmentResponse(
                adjustment.getId(),
                adjustment.getType().name(),
                adjustment.getAmount(),
                adjustment.getFeeAmount(),
                adjustment.getCurrency(),
                adjustment.getReason(),
                adjustment.getJournalId(),
                adjustment.getCorrelationId(),
                SensitiveDataMasking.maskIdempotencyKey(adjustment.getIdempotencyKey()),
                adjustment.getCreatedAt()
        );
    }
}
