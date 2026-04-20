package com.ledgerforge.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalTransactionRepository extends JpaRepository<JournalTransactionEntity, UUID> {
    Optional<JournalTransactionEntity> findFirstByTypeAndReferenceId(JournalType type, String referenceId);
}
