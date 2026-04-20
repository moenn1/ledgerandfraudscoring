package com.ledgerforge.payments.notification.api;

import com.ledgerforge.payments.notification.NotificationDeliveryEntity;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryResponse(
        UUID id,
        UUID endpointId,
        UUID paymentId,
        String eventType,
        String correlationId,
        String status,
        String receiptStatus,
        int attemptCount,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Integer lastResponseStatus,
        String lastResponseBody,
        String lastError,
        Instant callbackReceivedAt,
        String callbackReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static WebhookDeliveryResponse from(NotificationDeliveryEntity delivery) {
        return new WebhookDeliveryResponse(
                delivery.getId(),
                delivery.getEndpointId(),
                delivery.getPaymentId(),
                delivery.getEventType(),
                delivery.getCorrelationId(),
                delivery.getStatus().name(),
                delivery.getReceiptStatus().name(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getLastAttemptAt(),
                delivery.getLastResponseStatus(),
                delivery.getLastResponseBody(),
                delivery.getLastError(),
                delivery.getCallbackReceivedAt(),
                delivery.getCallbackReason(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt()
        );
    }
}
