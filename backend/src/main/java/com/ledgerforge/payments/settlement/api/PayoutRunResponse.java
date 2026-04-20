package com.ledgerforge.payments.settlement.api;

import java.time.Instant;
import java.util.List;

public record PayoutRunResponse(
        Instant asOf,
        int paidCount,
        int delayedCount,
        List<PayoutResponse> payouts
) {
}
