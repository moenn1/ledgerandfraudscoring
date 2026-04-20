package com.ledgerforge.payments.notification.api;

import java.time.Instant;
import java.util.UUID;

public record WebhookCallbackResponse(
        String callbackId,
        UUID deliveryId,
        boolean duplicate,
        boolean matched,
        String deliveryStatus,
        String receiptStatus,
        Instant receivedAt,
        Instant nextAttemptAt
) {
}
