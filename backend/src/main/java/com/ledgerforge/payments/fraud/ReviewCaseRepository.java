package com.ledgerforge.payments.fraud;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewCaseRepository extends JpaRepository<ReviewCaseEntity, UUID> {

    Optional<ReviewCaseEntity> findByPaymentId(UUID paymentId);

    long countByStatus(ReviewCaseStatus status);

    @Query("""
            select min(r.createdAt)
            from ReviewCaseEntity r
            where r.status = :status
            """)
    Optional<Instant> findOldestCreatedAtByStatus(@Param("status") ReviewCaseStatus status);

    @Query("""
            select r
            from ReviewCaseEntity r
            order by case when r.status = com.ledgerforge.payments.fraud.ReviewCaseStatus.OPEN then 0 else 1 end,
                     r.createdAt asc
            """)
    List<ReviewCaseEntity> findQueue();
}
