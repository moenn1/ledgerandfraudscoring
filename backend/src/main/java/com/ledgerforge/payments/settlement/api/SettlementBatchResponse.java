package com.ledgerforge.payments.settlement.api;

import com.ledgerforge.payments.settlement.SettlementBatchEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementBatchResponse(
        UUID id,
        Instant cutoffAt,
        String currency,
        String status,
        Integer paymentCount,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        Instant createdAt,
        Instant completedAt
) {
    public static SettlementBatchResponse from(SettlementBatchEntity batch) {
        return new SettlementBatchResponse(
                batch.getId(),
                batch.getCutoffAt(),
                batch.getCurrency(),
                batch.getStatus().name(),
                batch.getPaymentCount(),
                batch.getGrossAmount(),
                batch.getFeeAmount(),
                batch.getNetAmount(),
                batch.getCreatedAt(),
                batch.getCompletedAt()
        );
    }
}
