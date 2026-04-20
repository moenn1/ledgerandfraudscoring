package com.ledgerforge.payments.outbox;

public record OutboxProcessingResponse(
        int scanned,
        int published,
        int retried,
        int deadLettered
) {
}
