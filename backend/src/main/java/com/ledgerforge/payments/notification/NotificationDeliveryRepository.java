package com.ledgerforge.payments.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, UUID> {
    List<NotificationDeliveryEntity> findAllByOrderByCreatedAtDesc();

    List<NotificationDeliveryEntity> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    List<NotificationDeliveryEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
            List<NotificationDeliveryStatus> statuses,
            Instant nextAttemptAt,
            Pageable pageable
    );

    Optional<NotificationDeliveryEntity> findByIdAndEndpointId(UUID id, UUID endpointId);
}
