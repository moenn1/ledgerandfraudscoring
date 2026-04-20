package com.ledgerforge.payments.outbox;

public interface OutboxPublisher {

    void publish(OutboxMessageEntity message) throws Exception;
}
