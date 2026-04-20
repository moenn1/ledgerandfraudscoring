package com.ledgerforge.payments.broker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BrokerConsumerReceiptRepository extends JpaRepository<BrokerConsumerReceiptEntity, UUID> {

    long countByConsumerNameAndEventId(String consumerName, UUID eventId);
}
