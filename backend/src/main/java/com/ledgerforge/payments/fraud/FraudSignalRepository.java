package com.ledgerforge.payments.fraud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FraudSignalRepository extends JpaRepository<FraudSignalEntity, UUID> {

    List<FraudSignalEntity> findByPaymentIdOrderByWeightDescCreatedAtAsc(UUID paymentId);
}
