package com.ledgerforge.payments.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JournalResponse(
        UUID id,
        JournalType type,
        JournalStatus status,
        String referenceId,
        Instant createdAt,
        List<LedgerEntryResponse> entries
) {
    public static JournalResponse from(JournalTransactionEntity journal, List<LedgerEntryEntity> entries) {
        return new JournalResponse(
                journal.getId(),
                journal.getType(),
                journal.getStatus(),
                journal.getReferenceId(),
                journal.getCreatedAt(),
                entries.stream().map(LedgerEntryResponse::from).toList()
        );
    }
}
