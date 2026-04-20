package com.ledgerforge.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalTransactionRepository extends JpaRepository<JournalTransactionEntity, UUID> {
    Optional<JournalTransactionEntity> findFirstByTypeAndReferenceId(JournalType type, String referenceId);

    List<JournalTransactionEntity> findAllByReferenceIdStartingWithOrderByCreatedAtAsc(String referencePrefix);
}
