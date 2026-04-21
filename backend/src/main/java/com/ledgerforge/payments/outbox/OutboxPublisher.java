package com.ledgerforge.payments.outbox;

public interface OutboxPublisher {

    void publish(OutboxEventEntity event) throws Exception;
}
