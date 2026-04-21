package com.ledgerforge.payments.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID journalId,
        UUID accountId,
        int lineNumber,
        String direction,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntryEntity entity) {
        return new LedgerEntryResponse(
                entity.getId(),
                entity.getJournal().getId(),
                entity.getAccountId(),
                entity.getLineNumber(),
                entity.getDirection().name(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getCreatedAt()
        );
    }
}
