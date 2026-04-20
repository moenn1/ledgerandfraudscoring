package com.ledgerforge.payments.settlement.api;

import com.ledgerforge.payments.settlement.PayoutEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PayoutResponse(
        UUID id,
        UUID settlementBatchId,
        UUID payeeAccountId,
        String currency,
        String status,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        Instant scheduledFor,
        Instant executedAt,
        UUID journalId,
        String delayReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static PayoutResponse from(PayoutEntity payout) {
        return new PayoutResponse(
                payout.getId(),
                payout.getSettlementBatchId(),
                payout.getPayeeAccountId(),
                payout.getCurrency(),
                payout.getStatus().name(),
                payout.getGrossAmount(),
                payout.getFeeAmount(),
                payout.getNetAmount(),
                payout.getScheduledFor(),
                payout.getExecutedAt(),
                payout.getJournalId(),
                payout.getDelayReason(),
                payout.getCreatedAt(),
                payout.getUpdatedAt()
        );
    }
}
