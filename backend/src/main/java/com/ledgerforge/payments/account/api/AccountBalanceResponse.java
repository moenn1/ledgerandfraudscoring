package com.ledgerforge.payments.account.api;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalanceResponse(
        UUID accountId,
        String currency,
        BigDecimal balance
) {
}
