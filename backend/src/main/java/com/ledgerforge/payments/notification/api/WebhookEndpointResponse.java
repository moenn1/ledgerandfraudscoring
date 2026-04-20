package com.ledgerforge.payments.notification.api;

import com.ledgerforge.payments.notification.NotificationEndpointEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WebhookEndpointResponse(
        UUID id,
        String name,
        String url,
        List<String> subscribedEvents,
        boolean active,
        int maxAttempts,
        String signingSecretMasked,
        Instant createdAt,
        Instant updatedAt
) {
    public static WebhookEndpointResponse from(NotificationEndpointEntity endpoint, String signingSecretMasked) {
        return new WebhookEndpointResponse(
                endpoint.getId(),
                endpoint.getName(),
                endpoint.getUrl(),
                List.of(endpoint.getSubscribedEventTypes().split(",")),
                endpoint.isActive(),
                endpoint.getMaxAttempts(),
                signingSecretMasked,
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt()
        );
    }
}
