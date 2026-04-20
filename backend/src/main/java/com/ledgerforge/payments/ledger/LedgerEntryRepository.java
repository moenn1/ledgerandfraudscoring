package com.ledgerforge.payments.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    List<LedgerEntryEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<LedgerEntryEntity> findByAccountIdAndCurrencyOrderByCreatedAtDesc(UUID accountId, String currency);

    List<LedgerEntryEntity> findByAccountIdOrderByCreatedAtAscIdAsc(UUID accountId);

    List<LedgerEntryEntity> findByAccountIdAndCurrencyOrderByCreatedAtAscIdAsc(UUID accountId, String currency);

    List<LedgerEntryEntity> findByJournal_IdOrderByCreatedAtAsc(UUID journalId);

    @Query("""
            select le
            from LedgerEntryEntity le
            where le.journal.referenceId like concat(:referencePrefix, '%')
            order by le.createdAt asc
            """)
    List<LedgerEntryEntity> findByReferencePrefixOrderByCreatedAtAsc(@Param("referencePrefix") String referencePrefix);

    @Query("""
            select coalesce(sum(case when le.direction = com.ledgerforge.payments.ledger.LedgerDirection.CREDIT then le.amount else -le.amount end), 0)
            from LedgerEntryEntity le
            where le.accountId = :accountId and le.currency = :currency
            """)
    BigDecimal projectedBalance(@Param("accountId") UUID accountId, @Param("currency") String currency);

    @Query("""
            select le.journal.id, sum(case when le.direction = com.ledgerforge.payments.ledger.LedgerDirection.CREDIT then le.amount else -le.amount end)
            from LedgerEntryEntity le
            group by le.journal.id
            having sum(case when le.direction = com.ledgerforge.payments.ledger.LedgerDirection.CREDIT then le.amount else -le.amount end) <> 0
            """)
    List<Object[]> findUnbalancedJournalAggregates();

    @Query("""
            select le.journal.id, count(distinct le.currency)
            from LedgerEntryEntity le
            group by le.journal.id
            having count(distinct le.currency) > 1
            """)
    List<Object[]> findMixedCurrencyJournalAggregates();
}
