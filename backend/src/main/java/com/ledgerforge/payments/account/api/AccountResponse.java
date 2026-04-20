package com.ledgerforge.payments.account.api;

import com.ledgerforge.payments.account.AccountEntity;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String ownerId,
        String currency,
        String status,
        Instant createdAt
) {
    public static AccountResponse from(AccountEntity account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getCurrency(),
                account.getStatus().name(),
                account.getCreatedAt()
        );
    }
}
