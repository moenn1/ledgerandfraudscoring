package com.ledgerforge.payments.outbox.api;

import com.ledgerforge.payments.outbox.OutboxEventEntity;
import com.ledgerforge.payments.outbox.OutboxEventState;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventResponse(
        UUID id,
        String eventType,
        UUID paymentId,
        UUID journalId,
        String correlationId,
        String payloadJson,
        OutboxEventState state,
        int attemptCount,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant publishedAt,
        Instant deadLetteredAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static OutboxEventResponse from(OutboxEventEntity event) {
        return new OutboxEventResponse(
                event.getId(),
                event.getEventType(),
                event.getPaymentId(),
                event.getJournalId(),
                event.getCorrelationId(),
                event.getPayloadJson(),
                stateFor(event),
                event.getAttemptCount(),
                event.getNextAttemptAt(),
                event.getLastAttemptAt(),
                event.getPublishedAt(),
                event.getDeadLetteredAt(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getUpdatedAt()
        );
    }

    private static OutboxEventState stateFor(OutboxEventEntity event) {
        if (event.getDeadLetteredAt() != null) {
            return OutboxEventState.DEAD_LETTER;
        }
        if (event.getPublishedAt() != null) {
            return OutboxEventState.PUBLISHED;
        }
        return OutboxEventState.PENDING;
    }
}
