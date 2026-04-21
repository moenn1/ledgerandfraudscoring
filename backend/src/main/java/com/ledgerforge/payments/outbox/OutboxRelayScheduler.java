package com.ledgerforge.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxService outboxService;
    private final OutboxProperties outboxProperties;

    public OutboxRelayScheduler(OutboxService outboxService, OutboxProperties outboxProperties) {
        this.outboxService = outboxService;
        this.outboxProperties = outboxProperties;
    }

    @Scheduled(fixedDelayString = "${ledgerforge.outbox.relay-fixed-delay-ms:5000}")
    public void relay() {
        if (!outboxProperties.isRelayEnabled()) {
            return;
        }
        OutboxProcessResponse response = outboxService.processReadyEvents(outboxProperties.getBatchSize());
        if (response.scanned() > 0) {
            log.info(
                    "Processed outbox relay batch scanned={} published={} retried={} deadLettered={}",
                    response.scanned(),
                    response.published(),
                    response.retried(),
                    response.deadLettered()
            );
        }
    }
}
