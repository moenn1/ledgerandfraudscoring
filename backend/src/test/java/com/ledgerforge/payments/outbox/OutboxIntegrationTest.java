package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ledgerforge.outbox.relay-enabled=false",
        "ledgerforge.outbox.base-delay-ms=1000",
        "ledgerforge.outbox.max-delay-ms=8000",
        "ledgerforge.outbox.max-attempts=2"
})
@AutoConfigureMockMvc
class OutboxIntegrationTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-20T18:30:00Z");

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAll();
        auditEventRepository.deleteAll();
    }

    @Test
    void auditAppend_enqueuesPendingOutboxMessage() throws Exception {
        UUID paymentId = UUID.randomUUID();

        auditService.append(
                "payment.captured",
                paymentId,
                null,
                null,
                "corr-outbox-1",
                Map.of("status", "CAPTURED", "amount", "125.00")
        );

        OutboxMessageEntity message = singleOutboxMessage();
        assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
        assertThat(message.getAttempts()).isZero();
        assertThat(message.getNextAttemptAt()).isEqualTo(FIXED_NOW);
        assertThat(message.getCorrelationId()).isEqualTo("corr-outbox-1");

        JsonNode payload = objectMapper.readTree(message.getPayloadJson());
        assertThat(payload.get("eventType").asText()).isEqualTo("payment.captured");
        assertThat(payload.get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(payload.at("/details/status").asText()).isEqualTo("CAPTURED");
    }

    @Test
    void processReadyMessages_marksMessagesPublishedAfterSuccessfulDelivery() throws Exception {
        auditService.append(
                "payment.created",
                UUID.randomUUID(),
                null,
                null,
                "corr-outbox-2",
                Map.of("status", "CREATED")
        );

        OutboxProcessingResponse response = outboxService.processReadyMessages(10);

        OutboxMessageEntity message = singleOutboxMessage();
        assertThat(response.scanned()).isEqualTo(1);
        assertThat(response.published()).isEqualTo(1);
        assertThat(response.retried()).isZero();
        assertThat(response.deadLettered()).isZero();
        assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PUBLISHED);
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getPublishedAt()).isEqualTo(FIXED_NOW);
        assertThat(message.getLastError()).isNull();
        verify(outboxPublisher).publish(any(OutboxMessageEntity.class));
    }

    @Test
    void processReadyMessages_schedulesRetryWithBackoff() throws Exception {
        doThrow(new IllegalStateException("broker unavailable")).when(outboxPublisher).publish(any(OutboxMessageEntity.class));

        auditService.append(
                "payment.review_required",
                UUID.randomUUID(),
                null,
                null,
                "corr-outbox-3",
                Map.of("status", "RISK_SCORING")
        );

        OutboxProcessingResponse response = outboxService.processReadyMessages(10);

        OutboxMessageEntity message = singleOutboxMessage();
        assertThat(response.scanned()).isEqualTo(1);
        assertThat(response.published()).isZero();
        assertThat(response.retried()).isEqualTo(1);
        assertThat(response.deadLettered()).isZero();
        assertThat(message.getStatus()).isEqualTo(OutboxMessageStatus.PENDING);
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getNextAttemptAt()).isEqualTo(FIXED_NOW.plusSeconds(1));
        assertThat(message.getLastError()).isEqualTo("broker unavailable");
        assertThat(message.getDeadLetteredAt()).isNull();
    }

    @Test
    void deadLetterMessages_canBeInspectedAndRequeued() throws Exception {
        doThrow(new PoisonMessageException("invalid payload")).when(outboxPublisher).publish(any(OutboxMessageEntity.class));

        auditService.append(
                "fraud.review_case.opened",
                UUID.randomUUID(),
                null,
                null,
                "corr-outbox-4",
                Map.of("status", "OPEN")
        );

        outboxService.processReadyMessages(10);
        OutboxMessageEntity deadLetter = singleOutboxMessage();

        assertThat(deadLetter.getStatus()).isEqualTo(OutboxMessageStatus.DEAD_LETTER);
        assertThat(deadLetter.getDeadLetteredAt()).isEqualTo(FIXED_NOW);
        assertThat(deadLetter.getLastError()).isEqualTo("invalid payload");

        mockMvc.perform(get("/api/admin/outbox/messages")
                        .param("status", "DEAD_LETTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(deadLetter.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("DEAD_LETTER"))
                .andExpect(jsonPath("$[0].payloadJson").isNotEmpty());

        mockMvc.perform(post("/api/admin/outbox/messages/{id}/requeue", deadLetter.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.lastError").isEmpty());
    }

    @Test
    void repeatedRetryFailures_moveMessageToDeadLetterAfterThreshold() throws Exception {
        doThrow(new IllegalStateException("broker still unavailable")).when(outboxPublisher).publish(any(OutboxMessageEntity.class));

        auditService.append(
                "payment.failed",
                UUID.randomUUID(),
                null,
                null,
                "corr-outbox-5",
                Map.of("status", "FAILED")
        );

        OutboxProcessingResponse firstAttempt = outboxService.processReadyMessages(10);
        OutboxMessageEntity message = singleOutboxMessage();
        message.setNextAttemptAt(FIXED_NOW.minusSeconds(1));
        outboxMessageRepository.save(message);

        OutboxProcessingResponse secondAttempt = outboxService.processReadyMessages(10);
        OutboxMessageEntity deadLetter = singleOutboxMessage();

        assertThat(firstAttempt.retried()).isEqualTo(1);
        assertThat(secondAttempt.deadLettered()).isEqualTo(1);
        assertThat(deadLetter.getStatus()).isEqualTo(OutboxMessageStatus.DEAD_LETTER);
        assertThat(deadLetter.getAttempts()).isEqualTo(2);
        assertThat(deadLetter.getDeadLetteredAt()).isEqualTo(FIXED_NOW);
        assertThat(deadLetter.getLastError()).isEqualTo("broker still unavailable");
    }

    private OutboxMessageEntity singleOutboxMessage() {
        return outboxMessageRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected one outbox message"));
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
