package com.ledgerforge.payments.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.common.security.SensitiveDataMasking;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void append(String eventType,
                       UUID paymentId,
                       UUID accountId,
                       UUID journalId,
                       String correlationId,
                       Map<String, Object> details) {
        appendWithActor(eventType, paymentId, accountId, journalId, correlationId, "system", "ledgerforge-backend", details);
    }

    @Transactional
    public void appendWithActor(String eventType,
                                UUID paymentId,
                                UUID accountId,
                                UUID journalId,
                                String correlationId,
                                String actorType,
                                String actorId,
                                Map<String, Object> details) {
        AuditEventEntity event = new AuditEventEntity();
        event.setEventType(eventType);
        event.setPaymentId(paymentId);
        event.setAccountId(accountId);
        event.setJournalId(journalId);
        event.setCorrelationId(correlationId);
        event.setActorType(actorType);
        event.setActorId(actorId);
        event.setDetailsJson(toJson(details));
        auditEventRepository.save(event);
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(SensitiveDataMasking.sanitizeMap(details));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize audit details", ex);
        }
    }
}
