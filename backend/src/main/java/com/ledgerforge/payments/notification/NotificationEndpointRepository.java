package com.ledgerforge.payments.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationEndpointRepository extends JpaRepository<NotificationEndpointEntity, UUID> {
    List<NotificationEndpointEntity> findAllByOrderByCreatedAtAsc();

    List<NotificationEndpointEntity> findByActiveTrueOrderByCreatedAtAsc();
}
