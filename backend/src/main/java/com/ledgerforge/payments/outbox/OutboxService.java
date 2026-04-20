package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.common.security.SensitiveDataMasking;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    public OutboxEventEntity enqueue(String eventType,
                                     String aggregateType,
                                     UUID aggregateId,
                                     String partitionKey,
                                     String correlationId,
                                     String idempotencyKey,
                                     Map<String, Object> payload) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPartitionKey(partitionKey);
        event.setCorrelationId(blankToNull(correlationId));
        event.setIdempotencyKey(SensitiveDataMasking.maskIdempotencyKey(blankToNull(idempotencyKey)));
        event.setPayloadJson(toJson(SensitiveDataMasking.sanitizeMap(payload)));
        event.setAttemptCount(0);
        return outboxEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<OutboxEventEntity> list(String statusFilter, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        return switch (normalizeStatus(statusFilter)) {
            case "PENDING" -> outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtDesc(page);
            case "PUBLISHED" -> outboxEventRepository.findByPublishedAtIsNotNullOrderByCreatedAtDesc(page);
            default -> outboxEventRepository.findAllByOrderByCreatedAtDesc(page);
        };
    }

    public DomainEventEnvelope toEnvelope(OutboxEventEntity event) {
        return new DomainEventEnvelope(
                event.getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventVersion(),
                event.getPartitionKey(),
                event.getCorrelationId(),
                SensitiveDataMasking.maskIdempotencyKey(event.getIdempotencyKey()),
                event.getCreatedAt(),
                readJson(event.getPayloadJson())
        );
    }

    public JsonNode readJson(String rawJson) {
        try {
            return SensitiveDataMasking.sanitizeJson(objectMapper.readTree(rawJson));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to deserialize outbox payload", ex);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize outbox payload", ex);
        }
    }

    private String normalizeStatus(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "ALL";
        }
        return statusFilter.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
