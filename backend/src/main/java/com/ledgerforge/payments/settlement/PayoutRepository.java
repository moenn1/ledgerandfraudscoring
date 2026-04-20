package com.ledgerforge.payments.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<PayoutEntity, UUID> {

    Optional<PayoutEntity> findFirstBySettlementBatchIdAndPayeeAccountId(UUID settlementBatchId, UUID payeeAccountId);

    List<PayoutEntity> findAllByOrderByScheduledForDescCreatedAtDesc();

    List<PayoutEntity> findByStatusInAndScheduledForLessThanEqualOrderByScheduledForAscCreatedAtAsc(
            Collection<PayoutStatus> statuses,
            Instant scheduledFor
    );
}
