package com.ledgerforge.payments.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {
    Optional<AccountEntity> findFirstByOwnerIdAndCurrencyAndStatus(String ownerId, String currency, AccountStatus status);
}
