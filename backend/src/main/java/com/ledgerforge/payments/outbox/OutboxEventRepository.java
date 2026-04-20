package com.ledgerforge.payments.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findAllByOrderByCreatedAtAsc();

    List<OutboxEventEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<OutboxEventEntity> findByPublishedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    List<OutboxEventEntity> findByPublishedAtIsNotNullOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select e.id
            from OutboxEventEntity e
            where e.publishedAt is null
              and e.availableAt <= :asOf
              and (e.leasedUntil is null or e.leasedUntil <= :asOf)
            order by e.createdAt asc
            """)
    List<UUID> findRelayCandidateIds(@Param("asOf") Instant asOf, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update OutboxEventEntity e
            set e.leaseOwner = :leaseOwner,
                e.leasedUntil = :leasedUntil,
                e.attemptCount = e.attemptCount + 1
            where e.id = :id
              and e.publishedAt is null
              and e.availableAt <= :asOf
              and (e.leasedUntil is null or e.leasedUntil <= :asOf)
            """)
    int claimForDelivery(@Param("id") UUID id,
                         @Param("asOf") Instant asOf,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("leasedUntil") Instant leasedUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update OutboxEventEntity e
            set e.publishedAt = :publishedAt,
                e.leasedUntil = null,
                e.leaseOwner = null,
                e.lastError = null
            where e.id = :id
              and e.leaseOwner = :leaseOwner
            """)
    int markPublished(@Param("id") UUID id,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("publishedAt") Instant publishedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update OutboxEventEntity e
            set e.availableAt = :availableAt,
                e.leasedUntil = null,
                e.leaseOwner = null,
                e.lastError = :lastError
            where e.id = :id
              and e.leaseOwner = :leaseOwner
            """)
    int markFailed(@Param("id") UUID id,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("availableAt") Instant availableAt,
                   @Param("lastError") String lastError);
}
