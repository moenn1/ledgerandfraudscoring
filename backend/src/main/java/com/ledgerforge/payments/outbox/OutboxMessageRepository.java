package com.ledgerforge.payments.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessageEntity, UUID> {

    List<OutboxMessageEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
            OutboxMessageStatus status,
            Instant nextAttemptAt,
            Pageable pageable
    );

    List<OutboxMessageEntity> findByStatusOrderByCreatedAtDesc(OutboxMessageStatus status, Pageable pageable);

    List<OutboxMessageEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OutboxMessageEntity> findById(UUID id);
}
