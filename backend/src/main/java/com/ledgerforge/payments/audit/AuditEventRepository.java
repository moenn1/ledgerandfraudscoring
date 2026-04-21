package com.ledgerforge.payments.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
    List<AuditEventEntity> findByPaymentIdInOrderByCreatedAtAsc(Collection<UUID> paymentIds);
}
