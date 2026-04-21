package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.common.api.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublisher outboxPublisher;
    private final OutboxProperties outboxProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTransaction;
    private final Counter publishedCounter;
    private final Counter retriedCounter;
    private final Counter deadLetteredCounter;

    public OutboxService(OutboxEventRepository outboxEventRepository,
                         OutboxPublisher outboxPublisher,
                         OutboxProperties outboxProperties,
                         ObjectMapper objectMapper,
                         Clock clock,
                         PlatformTransactionManager transactionManager,
                         MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxPublisher = outboxPublisher;
        this.outboxProperties = outboxProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Gauge.builder("ledgerforge.outbox.queue.depth", outboxEventRepository, OutboxEventRepository::countPending)
                .description("Count of pending outbox events waiting for relay delivery")
                .register(meterRegistry);
        Gauge.builder("ledgerforge.outbox.queue.lag.seconds", this::pendingLagSeconds)
                .description("Age in seconds of the oldest pending outbox event")
                .register(meterRegistry);
        this.publishedCounter = Counter.builder("ledgerforge.outbox.publish.success")
                .description("Outbox events successfully published by the relay")
                .register(meterRegistry);
        this.retriedCounter = Counter.builder("ledgerforge.outbox.publish.retry")
                .description("Outbox relay publish attempts rescheduled for retry")
                .register(meterRegistry);
        this.deadLetteredCounter = Counter.builder("ledgerforge.outbox.publish.dead_letter")
                .description("Outbox events moved to dead-letter state")
                .register(meterRegistry);
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
        event.setAttemptCount(0);
        event.setNextAttemptAt(Instant.now(clock));
        outboxEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<OutboxEventEntity> list(OutboxEventState state, int limit) {
        PageRequest page = PageRequest.of(0, sanitizeLimit(limit));
        if (state == null) {
            return outboxEventRepository.findRecent(page);
        }
        return switch (state) {
            case PENDING -> outboxEventRepository.findPending(page);
            case PUBLISHED -> outboxEventRepository.findPublished(page);
            case DEAD_LETTER -> outboxEventRepository.findDeadLettered(page);
        };
    }

    public OutboxProcessResponse processReadyEvents(int limit) {
        List<ClaimedOutboxEvent> claimedEvents = transactionTemplate.execute(status -> claimReadyEvents(limit));
        if (claimedEvents == null) {
            claimedEvents = List.of();
        }

        int published = 0;
        int retried = 0;
        int deadLettered = 0;

        for (ClaimedOutboxEvent claimedEvent : claimedEvents) {
            DeliveryOutcome outcome = requiresNewTransaction.execute(status ->
                    publishClaimedEvent(claimedEvent.id(), claimedEvent.claimToken())
            );
            if (outcome == DeliveryOutcome.PUBLISHED) {
                published++;
            } else if (outcome == DeliveryOutcome.RETRIED) {
                retried++;
            } else if (outcome == DeliveryOutcome.DEAD_LETTERED) {
                deadLettered++;
            }
        }

        return new OutboxProcessResponse(claimedEvents.size(), published, retried, deadLettered);
    }

    @Transactional
    public OutboxEventEntity requeue(UUID eventId) {
        OutboxEventEntity event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Outbox event not found: " + eventId));
        if (event.getDeadLetteredAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Only dead-lettered outbox events can be requeued");
        }

        Instant now = Instant.now(clock);
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setLastAttemptAt(null);
        event.setDeadLetteredAt(null);
        event.setLastError(null);
        event.setClaimToken(null);
        event.setClaimExpiresAt(null);
        event.setPublishedAt(null);
        return outboxEventRepository.save(event);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize outbox payload", ex);
        }
    }

    @Transactional
    protected List<ClaimedOutboxEvent> claimReadyEvents(int limit) {
        Instant now = Instant.now(clock);
        Instant claimExpiresAt = now.plusMillis(outboxProperties.getClaimTimeoutMs());
        List<OutboxEventEntity> readyEvents = outboxEventRepository.findReadyForClaim(now, PageRequest.of(0, sanitizeLimit(limit)));

        List<ClaimedOutboxEvent> claimedEvents = readyEvents.stream()
                .map(event -> {
                    String claimToken = UUID.randomUUID().toString();
                    event.setClaimToken(claimToken);
                    event.setClaimExpiresAt(claimExpiresAt);
                    return new ClaimedOutboxEvent(event.getId(), claimToken);
                })
                .toList();

        outboxEventRepository.saveAll(readyEvents);
        return claimedEvents;
    }

    private DeliveryOutcome publishClaimedEvent(UUID eventId, String claimToken) {
        OutboxEventEntity event = outboxEventRepository.findByIdAndClaimToken(eventId, claimToken).orElse(null);
        if (event == null) {
            return DeliveryOutcome.SKIPPED;
        }

        Instant now = Instant.now(clock);
        if (event.getPublishedAt() != null || event.getDeadLetteredAt() != null) {
            clearClaim(event);
            outboxEventRepository.save(event);
            return DeliveryOutcome.SKIPPED;
        }
        if (event.getClaimExpiresAt() != null && event.getClaimExpiresAt().isBefore(now)) {
            clearClaim(event);
            outboxEventRepository.save(event);
            return DeliveryOutcome.SKIPPED;
        }

        int attemptNumber = event.getAttemptCount() + 1;
        event.setAttemptCount(attemptNumber);
        event.setLastAttemptAt(now);

        try {
            outboxPublisher.publish(event);
            clearClaim(event);
            event.setPublishedAt(now);
            event.setDeadLetteredAt(null);
            event.setLastError(null);
            event.setNextAttemptAt(now);
            outboxEventRepository.save(event);
            publishedCounter.increment();
            return DeliveryOutcome.PUBLISHED;
        } catch (PoisonMessageException ex) {
            deadLetter(event, now, ex);
            deadLetteredCounter.increment();
            return DeliveryOutcome.DEAD_LETTERED;
        } catch (Exception ex) {
            if (attemptNumber >= outboxProperties.getMaxAttempts()) {
                deadLetter(event, now, ex);
                deadLetteredCounter.increment();
                return DeliveryOutcome.DEAD_LETTERED;
            }
            retryLater(event, attemptNumber, now, ex);
            retriedCounter.increment();
            return DeliveryOutcome.RETRIED;
        }
    }

    private void retryLater(OutboxEventEntity event, int attemptNumber, Instant now, Exception ex) {
        clearClaim(event);
        event.setPublishedAt(null);
        event.setDeadLetteredAt(null);
        event.setLastError(trimError(ex));
        event.setNextAttemptAt(now.plusMillis(backoffDelayMs(attemptNumber)));
        outboxEventRepository.save(event);
    }

    private void deadLetter(OutboxEventEntity event, Instant now, Exception ex) {
        clearClaim(event);
        event.setPublishedAt(null);
        event.setDeadLetteredAt(now);
        event.setLastError(trimError(ex));
        event.setNextAttemptAt(now);
        outboxEventRepository.save(event);
    }

    private void clearClaim(OutboxEventEntity event) {
        event.setClaimToken(null);
        event.setClaimExpiresAt(null);
    }

    private long backoffDelayMs(int attemptNumber) {
        long multiplier = 1L << Math.min(Math.max(attemptNumber - 1, 0), 16);
        long delay;
        try {
            delay = Math.multiplyExact(outboxProperties.getBaseDelayMs(), multiplier);
        } catch (ArithmeticException ex) {
            delay = Long.MAX_VALUE;
        }
        return Math.min(delay, outboxProperties.getMaxDelayMs());
    }

    private int sanitizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private String trimError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        if (message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }

    private double pendingLagSeconds() {
        return outboxEventRepository.findOldestPendingCreatedAt()
                .map(createdAt -> Math.max(0, Duration.between(createdAt, Instant.now(clock)).toSeconds()))
                .orElse(0L);
    }

    private enum DeliveryOutcome {
        PUBLISHED,
        RETRIED,
        DEAD_LETTERED,
        SKIPPED
    }

    private record ClaimedOutboxEvent(UUID id, String claimToken) {
    }
}
