package com.ledgerforge.payments.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDeliveryEntity {

    @Id
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "event_type", nullable = false, length = 96)
    private String eventType;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private NotificationDeliveryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_status", nullable = false, length = 24)
    private NotificationReceiptStatus receiptStatus;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_response_status")
    private Integer lastResponseStatus;

    @Column(name = "last_response_body", length = 2000)
    private String lastResponseBody;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "callback_received_at")
    private Instant callbackReceivedAt;

    @Column(name = "callback_reason", length = 512)
    private String callbackReason;

    @Column(name = "last_signature", length = 256)
    private String lastSignature;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public void setEndpointId(UUID endpointId) {
        this.endpointId = endpointId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public NotificationDeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationDeliveryStatus status) {
        this.status = status;
    }

    public NotificationReceiptStatus getReceiptStatus() {
        return receiptStatus;
    }

    public void setReceiptStatus(NotificationReceiptStatus receiptStatus) {
        this.receiptStatus = receiptStatus;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Integer getLastResponseStatus() {
        return lastResponseStatus;
    }

    public void setLastResponseStatus(Integer lastResponseStatus) {
        this.lastResponseStatus = lastResponseStatus;
    }

    public String getLastResponseBody() {
        return lastResponseBody;
    }

    public void setLastResponseBody(String lastResponseBody) {
        this.lastResponseBody = lastResponseBody;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCallbackReceivedAt() {
        return callbackReceivedAt;
    }

    public void setCallbackReceivedAt(Instant callbackReceivedAt) {
        this.callbackReceivedAt = callbackReceivedAt;
    }

    public String getCallbackReason() {
        return callbackReason;
    }

    public void setCallbackReason(String callbackReason) {
        this.callbackReason = callbackReason;
    }

    public String getLastSignature() {
        return lastSignature;
    }

    public void setLastSignature(String lastSignature) {
        this.lastSignature = lastSignature;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
