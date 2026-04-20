package com.ledgerforge.payments.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.broker.BrokerConsumerReceiptService;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.outbox.DomainEventEnvelope;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentIntentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class NotificationEventWorkflowService {

    private final BrokerConsumerReceiptService brokerConsumerReceiptService;
    private final PaymentIntentRepository paymentIntentRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationEventWorkflowService(BrokerConsumerReceiptService brokerConsumerReceiptService,
                                            PaymentIntentRepository paymentIntentRepository,
                                            NotificationService notificationService,
                                            ObjectMapper objectMapper) {
        this.brokerConsumerReceiptService = brokerConsumerReceiptService;
        this.paymentIntentRepository = paymentIntentRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handle(String consumerName,
                       DomainEventEnvelope envelope,
                       String topic,
                       int partition,
                       long offset) {
        if (!shouldFanOut(envelope)) {
            return;
        }
        if (!brokerConsumerReceiptService.tryRecord(consumerName, envelope, topic, partition, offset)) {
            return;
        }

        PaymentIntentEntity payment = paymentIntentRepository.findById(envelope.aggregateId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found for broker event: " + envelope.aggregateId()));

        notificationService.enqueuePaymentEventFromBroker(
                envelope.eventType(),
                payment,
                envelope.correlationId(),
                envelope.payload() == null || envelope.payload().isMissingNode()
                        ? Map.of()
                        : objectMapper.convertValue(envelope.payload(), new TypeReference<Map<String, Object>>() {
                        })
        );
    }

    private boolean shouldFanOut(DomainEventEnvelope envelope) {
        return "payment".equals(envelope.aggregateType()) && envelope.eventType() != null && envelope.eventType().startsWith("payment.");
    }
}
