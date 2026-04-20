package com.ledgerforge.payments.account.api;

import com.ledgerforge.payments.account.AccountEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String ownerId,
        String currency,
        List<String> supportedCurrencies,
        String status,
        Instant createdAt
) {
    public static AccountResponse from(AccountEntity account, List<String> supportedCurrencies) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getCurrency(),
                supportedCurrencies,
                account.getStatus().name(),
                account.getCreatedAt()
        );
    }
}
