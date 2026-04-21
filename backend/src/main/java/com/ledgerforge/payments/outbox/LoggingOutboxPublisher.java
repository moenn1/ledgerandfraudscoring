package com.ledgerforge.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxEventEntity event) {
        log.info(
                "Published outbox event type={} paymentId={} journalId={} correlationId={} payload={}",
                event.getEventType(),
                event.getPaymentId(),
                event.getJournalId(),
                event.getCorrelationId(),
                event.getPayloadJson()
        );
    }
}
