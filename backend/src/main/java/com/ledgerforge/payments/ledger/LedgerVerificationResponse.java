package com.ledgerforge.payments.ledger;

import com.ledgerforge.payments.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerVerificationResponse(
        Instant generatedAt,
        long totalJournals,
        long totalEntries,
        boolean allChecksPassed,
        int issueCount,
        List<UnbalancedJournalFinding> unbalancedJournals,
        List<MixedCurrencyJournalFinding> mixedCurrencyJournals,
        List<MutationEventReconciliationFinding> mutationEventReconciliationFindings,
        List<PaymentLifecycleMismatchFinding> paymentLifecycleMismatches
) {
    public record UnbalancedJournalFinding(
            UUID journalId,
            JournalType type,
            String referenceId,
            BigDecimal netAmount
    ) {
    }

    public record MixedCurrencyJournalFinding(
            UUID journalId,
            JournalType type,
            String referenceId,
            List<String> currencies
    ) {
    }

    public record MutationEventReconciliationFinding(
            UUID paymentId,
            JournalType journalType,
            String action,
            String eventSink,
            String eventType,
            int ledgerMutationCount,
            int observedEventCount,
            int missingEventCount,
            int duplicateEventCount,
            List<UUID> journalIds,
            List<UUID> eventIds,
            String reason
    ) {
    }

    public record PaymentLifecycleMismatchFinding(
            UUID paymentId,
            PaymentStatus paymentStatus,
            List<JournalType> actualJournalTypes,
            List<List<JournalType>> acceptedJournalTypeSets,
            List<JournalType> missingJournalTypes,
            List<JournalType> unexpectedJournalTypes,
            String reason
    ) {
    }
}
