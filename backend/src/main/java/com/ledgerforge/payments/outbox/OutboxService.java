package com.ledgerforge.payments.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.audit.AuditEventEntity;
import com.ledgerforge.payments.common.api.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private final OutboxMessageRepository outboxMessageRepository;
    private final OutboxPublisher outboxPublisher;
    private final OutboxProperties outboxProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public OutboxService(OutboxMessageRepository outboxMessageRepository,
                         OutboxPublisher outboxPublisher,
                         OutboxProperties outboxProperties,
                         ObjectMapper objectMapper,
                         Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.outboxMessageRepository = outboxMessageRepository;
        this.outboxPublisher = outboxPublisher;
        this.outboxProperties = outboxProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public void enqueueAuditEvent(AuditEventEntity auditEvent, Map<String, Object> details) {
        OutboxMessageEntity message = new OutboxMessageEntity();
        message.setDestination(outboxProperties.getDestination());
        message.setEventType(auditEvent.getEventType());
        message.setAggregateType("audit_event");
        message.setAggregateId(auditEvent.getId());
        message.setCorrelationId(auditEvent.getCorrelationId());
        message.setPayloadJson(toJson(buildPayload(auditEvent, details)));
        message.setStatus(OutboxMessageStatus.PENDING);
        message.setAttempts(0);
        message.setMaxAttempts(outboxProperties.getMaxAttempts());
        message.setNextAttemptAt(Instant.now(clock));
        message.setLastError(null);
        outboxMessageRepository.save(message);
    }

    @Transactional(readOnly = true)
    public List<OutboxMessageEntity> list(OutboxMessageStatus status, int limit) {
        PageRequest page = PageRequest.of(0, sanitizeLimit(limit));
        if (status == null) {
            return outboxMessageRepository.findAllByOrderByCreatedAtDesc(page);
        }
        return outboxMessageRepository.findByStatusOrderByCreatedAtDesc(status, page);
    }

    public OutboxProcessingResponse processReadyMessages(int limit) {
        Instant now = Instant.now(clock);
        List<OutboxMessageEntity> readyMessages = outboxMessageRepository
                .findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                        OutboxMessageStatus.PENDING,
                        now,
                        PageRequest.of(0, sanitizeLimit(limit))
                );

        int published = 0;
        int retried = 0;
        int deadLettered = 0;

        for (OutboxMessageEntity message : readyMessages) {
            DeliveryOutcome outcome = requiresNewTransaction.execute(status -> deliver(message.getId()));
            if (outcome == DeliveryOutcome.PUBLISHED) {
                published++;
            } else if (outcome == DeliveryOutcome.RETRIED) {
                retried++;
            } else if (outcome == DeliveryOutcome.DEAD_LETTERED) {
                deadLettered++;
            }
        }

        return new OutboxProcessingResponse(readyMessages.size(), published, retried, deadLettered);
    }

    @Transactional
    public OutboxMessageEntity requeue(UUID messageId) {
        OutboxMessageEntity message = getOrFailForUpdate(messageId);
        if (message.getStatus() != OutboxMessageStatus.DEAD_LETTER) {
            throw new ApiException(HttpStatus.CONFLICT, "Only dead-letter messages can be requeued");
        }

        Instant now = Instant.now(clock);
        message.setStatus(OutboxMessageStatus.PENDING);
        message.setAttempts(0);
        message.setNextAttemptAt(now);
        message.setLastAttemptAt(null);
        message.setPublishedAt(null);
        message.setDeadLetteredAt(null);
        message.setLastError(null);
        return outboxMessageRepository.save(message);
    }

    private DeliveryOutcome deliver(UUID messageId) {
        OutboxMessageEntity message = getOrFailForUpdate(messageId);
        Instant now = Instant.now(clock);

        if (message.getStatus() != OutboxMessageStatus.PENDING || message.getNextAttemptAt().isAfter(now)) {
            return DeliveryOutcome.SKIPPED;
        }

        int attemptNumber = message.getAttempts() + 1;
        message.setAttempts(attemptNumber);
        message.setLastAttemptAt(now);

        try {
            outboxPublisher.publish(message);
            message.setStatus(OutboxMessageStatus.PUBLISHED);
            message.setPublishedAt(now);
            message.setDeadLetteredAt(null);
            message.setLastError(null);
            message.setNextAttemptAt(now);
            outboxMessageRepository.save(message);
            return DeliveryOutcome.PUBLISHED;
        } catch (PoisonMessageException ex) {
            deadLetter(message, now, ex);
            return DeliveryOutcome.DEAD_LETTERED;
        } catch (Exception ex) {
            if (attemptNumber >= message.getMaxAttempts()) {
                deadLetter(message, now, ex);
                return DeliveryOutcome.DEAD_LETTERED;
            }
            retryLater(message, attemptNumber, now, ex);
            return DeliveryOutcome.RETRIED;
        }
    }

    private void retryLater(OutboxMessageEntity message, int attemptNumber, Instant now, Exception ex) {
        message.setStatus(OutboxMessageStatus.PENDING);
        message.setPublishedAt(null);
        message.setDeadLetteredAt(null);
        message.setLastError(trimError(ex));
        message.setNextAttemptAt(now.plusMillis(backoffDelayMs(attemptNumber)));
        outboxMessageRepository.save(message);
    }

    private void deadLetter(OutboxMessageEntity message, Instant now, Exception ex) {
        message.setStatus(OutboxMessageStatus.DEAD_LETTER);
        message.setPublishedAt(null);
        message.setDeadLetteredAt(now);
        message.setLastError(trimError(ex));
        message.setNextAttemptAt(now);
        outboxMessageRepository.save(message);
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

    private OutboxMessageEntity getOrFail(UUID messageId) {
        return outboxMessageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Outbox message not found: " + messageId));
    }

    private OutboxMessageEntity getOrFailForUpdate(UUID messageId) {
        return outboxMessageRepository.findById(messageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Outbox message not found: " + messageId));
    }

    private Map<String, Object> buildPayload(AuditEventEntity auditEvent, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditEventId", auditEvent.getId());
        payload.put("eventType", auditEvent.getEventType());
        payload.put("paymentId", auditEvent.getPaymentId());
        payload.put("accountId", auditEvent.getAccountId());
        payload.put("journalId", auditEvent.getJournalId());
        payload.put("correlationId", auditEvent.getCorrelationId());
        payload.put("createdAt", auditEvent.getCreatedAt());
        payload.put("details", details == null ? Map.of() : details);
        return payload;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize outbox payload", ex);
        }
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

    private enum DeliveryOutcome {
        PUBLISHED,
        RETRIED,
        DEAD_LETTERED,
        SKIPPED
    }
}
