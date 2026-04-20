package com.ledgerforge.payments.notification.api;

import java.time.Instant;
import java.util.List;

public record DispatchWebhookDeliveriesResponse(
        Instant dispatchedAt,
        int processedCount,
        int succeededCount,
        int retryingCount,
        int failedCount,
        List<WebhookDeliveryResponse> deliveries
) {
}
