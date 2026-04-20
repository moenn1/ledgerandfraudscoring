package com.ledgerforge.payments.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationCallbackRepository extends JpaRepository<NotificationCallbackEntity, UUID> {
    Optional<NotificationCallbackEntity> findByEndpointIdAndCallbackId(UUID endpointId, String callbackId);
}
