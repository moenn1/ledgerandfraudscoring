package com.ledgerforge.payments.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    List<OutboxEventEntity> findByPaymentIdInOrderByCreatedAtAsc(Collection<UUID> paymentIds);
}
