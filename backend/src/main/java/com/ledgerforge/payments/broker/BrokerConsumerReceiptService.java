package com.ledgerforge.payments.broker;

import com.ledgerforge.payments.outbox.DomainEventEnvelope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrokerConsumerReceiptService {

    private final BrokerConsumerReceiptRepository brokerConsumerReceiptRepository;

    public BrokerConsumerReceiptService(BrokerConsumerReceiptRepository brokerConsumerReceiptRepository) {
        this.brokerConsumerReceiptRepository = brokerConsumerReceiptRepository;
    }

    @Transactional
    public boolean tryRecord(String consumerName,
                             DomainEventEnvelope envelope,
                             String topicName,
                             int partitionNumber,
                             long recordOffset) {
        BrokerConsumerReceiptEntity receipt = new BrokerConsumerReceiptEntity();
        receipt.setConsumerName(consumerName);
        receipt.setEventId(envelope.eventId());
        receipt.setEventType(envelope.eventType());
        receipt.setAggregateType(envelope.aggregateType());
        receipt.setAggregateId(envelope.aggregateId());
        receipt.setTopicName(topicName);
        receipt.setPartitionNumber(partitionNumber);
        receipt.setRecordOffset(recordOffset);
        try {
            brokerConsumerReceiptRepository.saveAndFlush(receipt);
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }
}
