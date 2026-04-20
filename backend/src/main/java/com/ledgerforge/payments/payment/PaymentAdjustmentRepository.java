package com.ledgerforge.payments.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentAdjustmentRepository extends JpaRepository<PaymentAdjustmentEntity, UUID> {

    List<PaymentAdjustmentEntity> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
