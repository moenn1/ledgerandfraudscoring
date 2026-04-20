package com.ledgerforge.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxMessageEntity message) {
        log.info(
                "Delivered outbox message id={} destination={} eventType={} attempts={}",
                message.getId(),
                message.getDestination(),
                message.getEventType(),
                message.getAttempts()
        );
    }
}
