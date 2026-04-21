package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final Instant FIXED_NOW = Instant.parse("2026-04-21T15:30:00Z");

    @Autowired
    private OutboxEventRepository outboxEventRepository;

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
        outboxEventRepository.deleteAll();
    }

    @Test
    void enqueue_createsPendingRelayEvent() throws Exception {
        UUID paymentId = UUID.randomUUID();

        outboxService.enqueue(
                "payment.captured",
                paymentId,
                null,
                "corr-outbox-1",
                Map.of("status", "CAPTURED", "amount", "125.00")
        );

        OutboxEventEntity event = singleEvent();
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(FIXED_NOW);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getDeadLetteredAt()).isNull();
        assertThat(event.getCorrelationId()).isEqualTo("corr-outbox-1");

        JsonNode payload = objectMapper.readTree(event.getPayloadJson());
        assertThat(payload.get("status").asText()).isEqualTo("CAPTURED");
        assertThat(payload.get("amount").asText()).isEqualTo("125.00");
    }

    @Test
    void processReadyEvents_marksPublishedAfterSuccessfulDelivery() throws Exception {
        outboxService.enqueue(
                "payment.created",
                UUID.randomUUID(),
                null,
                "corr-outbox-2",
                Map.of("status", "CREATED")
        );

        OutboxProcessResponse response = outboxService.processReadyEvents(10);

        OutboxEventEntity event = singleEvent();
        assertThat(response.scanned()).isEqualTo(1);
        assertThat(response.published()).isEqualTo(1);
        assertThat(response.retried()).isZero();
        assertThat(response.deadLettered()).isZero();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getPublishedAt()).isEqualTo(FIXED_NOW);
        assertThat(event.getDeadLetteredAt()).isNull();
        assertThat(event.getClaimToken()).isNull();
        assertThat(event.getLastError()).isNull();
        verify(outboxPublisher).publish(any(OutboxEventEntity.class));
    }

    @Test
    void processReadyEvents_schedulesRetryWithBackoff() throws Exception {
        doThrow(new IllegalStateException("broker unavailable")).when(outboxPublisher).publish(any(OutboxEventEntity.class));

        outboxService.enqueue(
                "payment.review_required",
                UUID.randomUUID(),
                null,
                "corr-outbox-3",
                Map.of("status", "RISK_SCORING")
        );

        OutboxProcessResponse response = outboxService.processReadyEvents(10);

        OutboxEventEntity event = singleEvent();
        assertThat(response.scanned()).isEqualTo(1);
        assertThat(response.published()).isZero();
        assertThat(response.retried()).isEqualTo(1);
        assertThat(response.deadLettered()).isZero();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getDeadLetteredAt()).isNull();
        assertThat(event.getNextAttemptAt()).isEqualTo(FIXED_NOW.plusSeconds(1));
        assertThat(event.getLastError()).isEqualTo("broker unavailable");
    }

    @Test
    void poisonMessages_canBeInspectedAndRequeued() throws Exception {
        doThrow(new PoisonMessageException("invalid payload")).when(outboxPublisher).publish(any(OutboxEventEntity.class));

        outboxService.enqueue(
                "fraud.review_case.opened",
                UUID.randomUUID(),
                null,
                "corr-outbox-4",
                Map.of("status", "OPEN")
        );

        OutboxProcessResponse response = outboxService.processReadyEvents(10);
        OutboxEventEntity event = singleEvent();

        assertThat(response.deadLettered()).isEqualTo(1);
        assertThat(event.getDeadLetteredAt()).isEqualTo(FIXED_NOW);
        assertThat(event.getLastError()).isEqualTo("invalid payload");

        mockMvc.perform(get("/api/outbox/events")
                        .param("state", "DEAD_LETTER")
                        .header("Authorization", com.ledgerforge.payments.security.TestOperatorTokens.bearer("admin.outbox@ledgerforge.local", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(event.getId().toString()))
                .andExpect(jsonPath("$[0].state").value("DEAD_LETTER"))
                .andExpect(jsonPath("$[0].lastError").value("invalid payload"));

        mockMvc.perform(post("/api/outbox/events/{eventId}/requeue", event.getId())
                        .header("Authorization", com.ledgerforge.payments.security.TestOperatorTokens.bearer("admin.outbox@ledgerforge.local", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.lastError").isEmpty());
    }

    @Test
    void repeatedFailures_moveEventToDeadLetterAfterThreshold() throws Exception {
        doThrow(new IllegalStateException("broker still unavailable")).when(outboxPublisher).publish(any(OutboxEventEntity.class));

        outboxService.enqueue(
                "payment.failed",
                UUID.randomUUID(),
                null,
                "corr-outbox-5",
                Map.of("status", "FAILED")
        );

        OutboxProcessResponse firstAttempt = outboxService.processReadyEvents(10);
        OutboxEventEntity event = singleEvent();
        event.setNextAttemptAt(FIXED_NOW.minusSeconds(1));
        outboxEventRepository.save(event);

        OutboxProcessResponse secondAttempt = outboxService.processReadyEvents(10);
        OutboxEventEntity deadLettered = singleEvent();

        assertThat(firstAttempt.retried()).isEqualTo(1);
        assertThat(secondAttempt.deadLettered()).isEqualTo(1);
        assertThat(deadLettered.getAttemptCount()).isEqualTo(2);
        assertThat(deadLettered.getDeadLetteredAt()).isEqualTo(FIXED_NOW);
        assertThat(deadLettered.getLastError()).isEqualTo("broker still unavailable");
    }

    @Test
    void actuatorMetrics_exposeQueueDepthAndLag() throws Exception {
        OutboxEventEntity staleEvent = new OutboxEventEntity();
        staleEvent.setEventType("payment.created");
        staleEvent.setPaymentId(UUID.randomUUID());
        staleEvent.setPayloadJson("{\"status\":\"CREATED\"}");
        staleEvent.setCreatedAt(FIXED_NOW.minusSeconds(90));
        staleEvent.setUpdatedAt(FIXED_NOW.minusSeconds(90));
        staleEvent.setNextAttemptAt(FIXED_NOW.minusSeconds(30));
        outboxEventRepository.save(staleEvent);

        mockMvc.perform(get("/actuator/metrics/ledgerforge.outbox.queue.depth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        mockMvc.perform(get("/actuator/metrics/ledgerforge.outbox.queue.lag.seconds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(90.0));
    }

    @Test
    void adminCanRunRelayOnDemand() throws Exception {
        outboxService.enqueue(
                "payment.reserved",
                UUID.randomUUID(),
                null,
                "corr-outbox-6",
                Map.of("status", "RESERVED")
        );

        mockMvc.perform(post("/api/outbox/relay/run")
                        .param("limit", "5")
                        .header("Authorization", com.ledgerforge.payments.security.TestOperatorTokens.bearer("admin.outbox@ledgerforge.local", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(1))
                .andExpect(jsonPath("$.published").value(1))
                .andExpect(jsonPath("$.retried").value(0))
                .andExpect(jsonPath("$.deadLettered").value(0));
    }

    private OutboxEventEntity singleEvent() {
        return outboxEventRepository.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a single outbox event"));
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
