package com.ledgerforge.payments.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateJournalRequest(
        @NotNull JournalType type,
        @Size(max = 128) String referenceId,
        @NotEmpty List<@Valid CreateLedgerLegRequest> entries
) {
}
