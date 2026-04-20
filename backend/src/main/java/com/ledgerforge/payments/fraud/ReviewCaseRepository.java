package com.ledgerforge.payments.fraud;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewCaseRepository extends JpaRepository<ReviewCaseEntity, UUID> {

    Optional<ReviewCaseEntity> findByPaymentId(UUID paymentId);

    @Query("""
            select r
            from ReviewCaseEntity r
            order by case when r.status = com.ledgerforge.payments.fraud.ReviewCaseStatus.OPEN then 0 else 1 end,
                     r.createdAt asc
            """)
    List<ReviewCaseEntity> findQueue();
}
