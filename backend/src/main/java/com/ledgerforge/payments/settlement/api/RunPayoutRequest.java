package com.ledgerforge.payments.settlement.api;

import java.time.Instant;

public record RunPayoutRequest(
        Instant asOf
) {
}
