package com.ledgerforge.payments.outbox.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerforge.payments.common.security.SensitiveDataMasking;
import com.ledgerforge.payments.outbox.OutboxEventEntity;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventResponse(
        UUID id,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        int eventVersion,
        String partitionKey,
        String correlationId,
        String idempotencyKey,
        String deliveryStatus,
        int attemptCount,
        Instant createdAt,
        Instant availableAt,
        Instant publishedAt,
        String lastError,
        JsonNode payload
) {

    public static OutboxEventResponse from(OutboxEventEntity event, JsonNode payload) {
        return new OutboxEventResponse(
                event.getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventVersion(),
                event.getPartitionKey(),
                event.getCorrelationId(),
                SensitiveDataMasking.maskIdempotencyKey(event.getIdempotencyKey()),
                event.getPublishedAt() == null ? "PENDING" : "PUBLISHED",
                event.getAttemptCount(),
                event.getCreatedAt(),
                event.getAvailableAt(),
                event.getPublishedAt(),
                event.getLastError(),
                payload
        );
    }
}
