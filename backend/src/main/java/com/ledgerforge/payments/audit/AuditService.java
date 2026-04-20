package com.ledgerforge.payments.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.outbox.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final OutboxService outboxService;

    public AuditService(AuditEventRepository auditEventRepository,
                        ObjectMapper objectMapper,
                        OutboxService outboxService) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
    }

    @Transactional
    public void append(String eventType,
                       UUID paymentId,
                       UUID accountId,
                       UUID journalId,
                       String correlationId,
                       Map<String, Object> details) {
        AuditEventEntity event = new AuditEventEntity();
        event.setEventType(eventType);
        event.setPaymentId(paymentId);
        event.setAccountId(accountId);
        event.setJournalId(journalId);
        event.setCorrelationId(correlationId);
        event.setDetailsJson(toJson(details));
        AuditEventEntity saved = auditEventRepository.save(event);
        outboxService.enqueueAuditEvent(saved, details);
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize audit details", ex);
        }
    }
}
