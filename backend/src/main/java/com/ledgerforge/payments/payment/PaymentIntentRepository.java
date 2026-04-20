package com.ledgerforge.payments.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntentEntity, UUID> {

    Optional<PaymentIntentEntity> findByIdempotencyKey(String idempotencyKey);

    List<PaymentIntentEntity> findAllByOrderByCreatedAtAsc();

    List<PaymentIntentEntity> findAllByOrderByCreatedAtDesc();

    List<PaymentIntentEntity> findByStatusAndSettlementScheduledForLessThanEqualOrderBySettlementScheduledForAscCreatedAtAsc(
            PaymentStatus status,
            Instant settlementScheduledFor
    );

    List<PaymentIntentEntity> findByStatusInOrderByCreatedAtAsc(Collection<PaymentStatus> statuses);

    long countByPayerAccountIdAndCreatedAtAfter(UUID payerAccountId, Instant after);

    long countByPayerAccountIdAndStatusAndUpdatedAtAfter(UUID payerAccountId, PaymentStatus status, Instant after);

    long countByPayerAccountIdAndIdNot(UUID payerAccountId, UUID excludedPaymentId);

    @Query("""
            select avg(pi.amount)
            from PaymentIntentEntity pi
            where pi.payerAccountId = :payerAccountId and pi.id <> :excludedPaymentId
            """)
    BigDecimal averageAmountForPayerExcludingPayment(@Param("payerAccountId") UUID payerAccountId,
                                                     @Param("excludedPaymentId") UUID excludedPaymentId);
}
