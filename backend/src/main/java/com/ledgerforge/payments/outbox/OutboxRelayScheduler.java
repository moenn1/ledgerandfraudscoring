package com.ledgerforge.payments.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayScheduler {

    private final OutboxService outboxService;
    private final OutboxProperties outboxProperties;

    public OutboxRelayScheduler(OutboxService outboxService, OutboxProperties outboxProperties) {
        this.outboxService = outboxService;
        this.outboxProperties = outboxProperties;
    }

    @Scheduled(fixedDelayString = "${ledgerforge.outbox.relay-delay-ms:5000}")
    public void relayReadyMessages() {
        if (!outboxProperties.isRelayEnabled()) {
            return;
        }
        outboxService.processReadyMessages(outboxProperties.getRelayBatchSize());
    }
}
