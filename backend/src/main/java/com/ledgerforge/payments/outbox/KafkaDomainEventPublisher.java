package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.broker.KafkaBrokerProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "ledgerforge.kafka", name = "enabled", havingValue = "true")
public class KafkaDomainEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaBrokerProperties properties;

    public KafkaDomainEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper,
                                     KafkaBrokerProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(DomainEventEnvelope envelope) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                properties.getTopics().getDomainEvents(),
                envelope.partitionKey(),
                serialize(envelope)
        );
        addHeader(record, "ledgerforge-event-id", envelope.eventId().toString());
        addHeader(record, "ledgerforge-event-type", envelope.eventType());
        addHeader(record, "ledgerforge-aggregate-type", envelope.aggregateType());
        if (envelope.correlationId() != null && !envelope.correlationId().isBlank()) {
            addHeader(record, "ledgerforge-correlation-id", envelope.correlationId());
        }

        try {
            kafkaTemplate.send(record).get(properties.getProducer().getSendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unable to publish domain event to Kafka", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Unable to publish domain event to Kafka", ex);
        }
    }

    private void addHeader(ProducerRecord<String, String> record, String key, String value) {
        record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private String serialize(DomainEventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize domain event envelope", ex);
        }
    }
}
