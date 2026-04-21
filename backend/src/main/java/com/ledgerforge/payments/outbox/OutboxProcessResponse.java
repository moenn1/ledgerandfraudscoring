package com.ledgerforge.payments.outbox;

public record OutboxProcessResponse(
        int scanned,
        int published,
        int retried,
        int deadLettered
) {
}
