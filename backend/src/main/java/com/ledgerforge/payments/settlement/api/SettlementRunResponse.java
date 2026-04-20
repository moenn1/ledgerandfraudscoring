package com.ledgerforge.payments.settlement.api;

import java.time.Instant;
import java.util.List;

public record SettlementRunResponse(
        Instant asOf,
        int settledPaymentCount,
        int batchCount,
        int payoutCount,
        List<SettlementBatchResponse> batches
) {
}
