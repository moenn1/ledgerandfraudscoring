package com.ledgerforge.payments.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.outbox.DomainEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "ledgerforge.kafka", name = "enabled", havingValue = "true")
public class BrokerDeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(BrokerDeadLetterListener.class);

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public BrokerDeadLetterListener(ObjectMapper objectMapper, AuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @KafkaListener(
            topics = "${ledgerforge.kafka.topics.domain-events-dlt:ledgerforge.domain-events.dlt}",
            groupId = "${ledgerforge.kafka.dead-letter.consumer-group-id:ledgerforge-broker-dlt}",
            containerFactory = "ledgerforgeDltListenerContainerFactory"
    )
    public void onDeadLetter(String rawEnvelope,
                             @Header(KafkaHeaders.RECEIVED_TOPIC) String deadLetterTopic,
                             @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) String originalTopic,
                             @Header(name = KafkaHeaders.DLT_ORIGINAL_PARTITION, required = false) Integer originalPartition,
                             @Header(name = KafkaHeaders.DLT_ORIGINAL_OFFSET, required = false) Long originalOffset,
                             @Header(name = KafkaHeaders.DLT_EXCEPTION_FQCN, required = false) String exceptionClass,
                             @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String exceptionMessage) {
        DomainEventEnvelope envelope = deserialize(rawEnvelope);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", envelope.eventId());
        details.put("eventType", envelope.eventType());
        details.put("aggregateType", envelope.aggregateType());
        details.put("aggregateId", envelope.aggregateId());
        details.put("deadLetterTopic", deadLetterTopic);
        details.put("originalTopic", originalTopic == null ? "" : originalTopic);
        details.put("originalPartition", originalPartition == null ? -1 : originalPartition);
        details.put("originalOffset", originalOffset == null ? -1 : originalOffset);
        details.put("exceptionClass", exceptionClass == null ? "" : exceptionClass);
        details.put("exceptionMessage", exceptionMessage == null ? "" : exceptionMessage);

        auditService.appendWithActor(
                "broker.consumer.dead_lettered",
                resolvePaymentId(envelope),
                null,
                null,
                envelope.correlationId(),
                "system",
                "kafka-dlt-monitor",
                details
        );

        log.warn(
                "Dead-lettered domain event id={} type={} aggregateType={} aggregateId={} originalTopic={} exception={}: {}",
                envelope.eventId(),
                envelope.eventType(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                originalTopic,
                exceptionClass,
                exceptionMessage
        );
    }

    private DomainEventEnvelope deserialize(String rawEnvelope) {
        try {
            return objectMapper.readValue(rawEnvelope, DomainEventEnvelope.class);
        } catch (Exception ex) {
            JsonNode fallback = parseJson(rawEnvelope);
            UUID aggregateId = readUuid(fallback, "aggregateId");
            return new DomainEventEnvelope(
                    readUuid(fallback, "eventId"),
                    fallback.path("eventType").asText("unknown"),
                    fallback.path("aggregateType").asText("unknown"),
                    aggregateId == null ? new UUID(0L, 0L) : aggregateId,
                    fallback.path("eventVersion").asInt(1),
                    fallback.path("partitionKey").asText("unknown"),
                    fallback.path("correlationId").asText(null),
                    fallback.path("idempotencyKey").asText(null),
                    null,
                    fallback.path("payload")
            );
        }
    }

    private JsonNode parseJson(String rawEnvelope) {
        try {
            return objectMapper.readTree(rawEnvelope);
        } catch (Exception ex) {
            return objectMapper.getNodeFactory().objectNode();
        }
    }

    private UUID readUuid(JsonNode node, String fieldName) {
        try {
            String value = node.path(fieldName).asText(null);
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private UUID resolvePaymentId(DomainEventEnvelope envelope) {
        if ("payment".equals(envelope.aggregateType())) {
            return envelope.aggregateId();
        }
        JsonNode payload = envelope.payload();
        if (payload == null || payload.isMissingNode()) {
            return null;
        }
        return readUuid(payload, "paymentId");
    }
}
