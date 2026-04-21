package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(String eventType,
                        UUID paymentId,
                        UUID journalId,
                        String correlationId,
                        Map<String, Object> payload) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventType(eventType);
        event.setPaymentId(paymentId);
        event.setJournalId(journalId);
        event.setCorrelationId(correlationId);
        event.setPayloadJson(toJson(payload));
        outboxEventRepository.save(event);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize outbox payload", ex);
        }
    }
}
