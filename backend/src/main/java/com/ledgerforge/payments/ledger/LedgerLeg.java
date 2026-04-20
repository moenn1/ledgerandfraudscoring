package com.ledgerforge.payments.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerLeg(
        UUID accountId,
        LedgerDirection direction,
        BigDecimal amount,
        String currency
) {
}
