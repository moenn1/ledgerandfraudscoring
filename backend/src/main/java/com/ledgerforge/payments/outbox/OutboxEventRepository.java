package com.ledgerforge.payments.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findByPaymentIdInOrderByCreatedAtAsc(Collection<UUID> paymentIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event
            from OutboxEventEntity event
            where event.publishedAt is null
              and event.deadLetteredAt is null
              and event.nextAttemptAt <= :asOf
              and (event.claimExpiresAt is null or event.claimExpiresAt <= :asOf)
            order by event.nextAttemptAt asc, event.createdAt asc
            """)
    List<OutboxEventEntity> findReadyForClaim(@Param("asOf") Instant asOf, Pageable pageable);

    Optional<OutboxEventEntity> findByIdAndClaimToken(UUID id, String claimToken);

    @Query("""
            select event
            from OutboxEventEntity event
            where event.deadLetteredAt is not null
            order by event.deadLetteredAt desc, event.createdAt desc
            """)
    List<OutboxEventEntity> findDeadLettered(Pageable pageable);

    @Query("""
            select event
            from OutboxEventEntity event
            where event.publishedAt is null
              and event.deadLetteredAt is null
            order by event.createdAt desc
            """)
    List<OutboxEventEntity> findPending(Pageable pageable);

    @Query("""
            select event
            from OutboxEventEntity event
            where event.publishedAt is not null
            order by event.publishedAt desc, event.createdAt desc
            """)
    List<OutboxEventEntity> findPublished(Pageable pageable);

    @Query("""
            select event
            from OutboxEventEntity event
            order by event.createdAt desc
            """)
    List<OutboxEventEntity> findRecent(Pageable pageable);

    @Query("""
            select count(event)
            from OutboxEventEntity event
            where event.publishedAt is null
              and event.deadLetteredAt is null
            """)
    long countPending();

    @Query("""
            select min(event.createdAt)
            from OutboxEventEntity event
            where event.publishedAt is null
              and event.deadLetteredAt is null
            """)
    Optional<Instant> findOldestPendingCreatedAt();
}
