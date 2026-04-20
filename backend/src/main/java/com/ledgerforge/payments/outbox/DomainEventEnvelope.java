package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record DomainEventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        int eventVersion,
        String partitionKey,
        String correlationId,
        String idempotencyKey,
        Instant createdAt,
        JsonNode payload
) {
}
