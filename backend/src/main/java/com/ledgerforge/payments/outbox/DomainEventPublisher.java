package com.ledgerforge.payments.outbox;

public interface DomainEventPublisher {

    void publish(DomainEventEnvelope envelope);
}
