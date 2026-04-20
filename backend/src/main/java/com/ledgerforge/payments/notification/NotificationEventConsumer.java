package com.ledgerforge.payments.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.outbox.DomainEventEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ledgerforge.kafka", name = "enabled", havingValue = "true")
public class NotificationEventConsumer {

    private static final String CONSUMER_NAME = "notification-webhooks";

    private final ObjectMapper objectMapper;
    private final NotificationEventWorkflowService notificationEventWorkflowService;

    public NotificationEventConsumer(ObjectMapper objectMapper,
                                     NotificationEventWorkflowService notificationEventWorkflowService) {
        this.objectMapper = objectMapper;
        this.notificationEventWorkflowService = notificationEventWorkflowService;
    }

    @KafkaListener(
            topics = "${ledgerforge.kafka.topics.domain-events:ledgerforge.domain-events}",
            groupId = "${ledgerforge.kafka.notifications.consumer-group-id:ledgerforge-notification-consumer}",
            containerFactory = "ledgerforgeKafkaListenerContainerFactory"
    )
    public void onMessage(String rawEnvelope,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                          @Header(KafkaHeaders.OFFSET) long offset) throws Exception {
        notificationEventWorkflowService.handle(
                CONSUMER_NAME,
                objectMapper.readValue(rawEnvelope, DomainEventEnvelope.class),
                topic,
                partition,
                offset
        );
    }
}
