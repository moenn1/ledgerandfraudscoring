package com.ledgerforge.payments.settlement.api;

import jakarta.validation.constraints.Min;

import java.time.Instant;

public record RunSettlementRequest(
        Instant asOf,
        @Min(0) Integer payoutDelayMinutes
) {
}
