package com.ledgerforge.payments.outbox.api;

import com.ledgerforge.payments.outbox.OutboxMessageEntity;
import com.ledgerforge.payments.outbox.OutboxMessageStatus;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessageResponse(
        UUID id,
        String destination,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String correlationId,
        String payloadJson,
        OutboxMessageStatus status,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant publishedAt,
        Instant deadLetteredAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {

    public static OutboxMessageResponse from(OutboxMessageEntity entity) {
        return new OutboxMessageResponse(
                entity.getId(),
                entity.getDestination(),
                entity.getEventType(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getCorrelationId(),
                entity.getPayloadJson(),
                entity.getStatus(),
                entity.getAttempts(),
                entity.getMaxAttempts(),
                entity.getNextAttemptAt(),
                entity.getLastAttemptAt(),
                entity.getPublishedAt(),
                entity.getDeadLetteredAt(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
