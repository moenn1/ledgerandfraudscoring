package com.ledgerforge.payments.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerReplayResponse(
        UUID accountId,
        String currency,
        int entryCount,
        BigDecimal projectedBalance,
        List<ReplayEntry> entries
) {
    public record ReplayEntry(
            UUID entryId,
            UUID journalId,
            JournalType journalType,
            String referenceId,
            LedgerDirection direction,
            BigDecimal amount,
            BigDecimal signedImpact,
            BigDecimal runningBalance,
            Instant createdAt
    ) {
    }
}
