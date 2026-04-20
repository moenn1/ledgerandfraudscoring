package com.ledgerforge.payments.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.audit.AuditEventEntity;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.notification.NotificationDeliveryRepository;
import com.ledgerforge.payments.notification.NotificationService;
import com.ledgerforge.payments.notification.api.CreateWebhookEndpointRequest;
import com.ledgerforge.payments.outbox.DomainEventEnvelope;
import com.ledgerforge.payments.outbox.OutboxEventEntity;
import com.ledgerforge.payments.outbox.OutboxEventRepository;
import com.ledgerforge.payments.outbox.OutboxRelayService;
import com.ledgerforge.payments.outbox.OutboxService;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentService;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ledgerforge.kafka.enabled=true",
        "ledgerforge.outbox.relay-enabled=false",
        "ledgerforge.kafka.consumer.backoff-ms=200",
        "ledgerforge.kafka.consumer.max-attempts=3",
        "spring.datasource.url=jdbc:h2:mem:ledgerforge-kafka;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@EmbeddedKafka(
        partitions = 3,
        topics = {"ledgerforge.domain-events", "ledgerforge.domain-events.dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DirtiesContext
class KafkaBrokerIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationDeliveryRepository notificationDeliveryRepository;

    @Autowired
    private BrokerConsumerReceiptRepository brokerConsumerReceiptRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void relayPublishesToKafka_andNotificationConsumerDeduplicatesByEventId() throws Exception {
        createEndpoint("Kafka payment consumer", List.of("payment.*"));

        UUID payerId = createAccount("broker-payer-" + UUID.randomUUID(), "USD");
        UUID payeeId = createAccount("broker-payee-" + UUID.randomUUID(), "USD");
        String idempotencyKey = "broker-create-" + UUID.randomUUID();

        PaymentIntentEntity payment = paymentService.createWithIdempotency(
                new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", idempotencyKey),
                idempotencyKey,
                "corr-broker-payment"
        );

        assertThat(notificationDeliveryRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId())).isEmpty();

        OutboxEventEntity event = outboxEventRepository.findAllByOrderByCreatedAtAsc().stream()
                .filter(candidate -> "payment.created".equals(candidate.getEventType()) && payment.getId().equals(candidate.getAggregateId()))
                .findFirst()
                .orElseThrow();

        int publishedCount = outboxRelayService.relayDueEvents(event.getCreatedAt().plusSeconds(1));
        assertThat(publishedCount).isEqualTo(1);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(notificationDeliveryRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId())).hasSize(1);
                    assertThat(brokerConsumerReceiptRepository.countByConsumerNameAndEventId("notification-webhooks", event.getId())).isEqualTo(1);
                });

        kafkaTemplate.send(
                "ledgerforge.domain-events",
                event.getPartitionKey(),
                objectMapper.writeValueAsString(outboxService.toEnvelope(event))
        ).get(5, TimeUnit.SECONDS);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(notificationDeliveryRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId())).hasSize(1);
                    assertThat(brokerConsumerReceiptRepository.countByConsumerNameAndEventId("notification-webhooks", event.getId())).isEqualTo(1);
                });
    }

    @Test
    void poisonNotificationEvents_areDeadLetteredAndAudited() throws Exception {
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                UUID.randomUUID(),
                "payment.created",
                "payment",
                UUID.randomUUID(),
                1,
                "payment:" + UUID.randomUUID(),
                "corr-broker-dlt",
                null,
                Instant.now(),
                objectMapper.valueToTree(Map.of(
                        "paymentId", UUID.randomUUID(),
                        "status", "CREATED",
                        "currency", "USD"
                ))
        );

        kafkaTemplate.send(
                "ledgerforge.domain-events",
                envelope.partitionKey(),
                objectMapper.writeValueAsString(envelope)
        ).get(5, TimeUnit.SECONDS);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    List<AuditEventEntity> deadLetters = auditEventRepository.findAll().stream()
                            .filter(event -> "broker.consumer.dead_lettered".equals(event.getEventType()))
                            .toList();
                    assertThat(deadLetters).isNotEmpty();
                    assertThat(deadLetters)
                            .anySatisfy(event -> {
                                assertThat(event.getCorrelationId()).isEqualTo("corr-broker-dlt");
                                assertThat(event.getDetailsJson()).contains(envelope.eventId().toString());
                            });
                });
    }

    private void createEndpoint(String name, List<String> subscribedEvents) {
        notificationService.createEndpoint(new CreateWebhookEndpointRequest(
                name,
                "http://127.0.0.1:9001/hooks",
                subscribedEvents,
                3,
                "broker-secret-" + UUID.randomUUID()
        ));
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
