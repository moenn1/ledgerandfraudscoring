package com.ledgerforge.payments.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatchEntity, UUID> {

    Optional<SettlementBatchEntity> findFirstByCutoffAtAndCurrency(Instant cutoffAt, String currency);

    List<SettlementBatchEntity> findAllByOrderByCutoffAtDescCreatedAtDesc();
}
