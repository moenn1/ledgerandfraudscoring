package com.ledgerforge.payments.fraud.api;

import com.ledgerforge.payments.fraud.ReviewCaseEntity;

import java.time.Instant;
import java.util.UUID;

public record ReviewCaseResponse(
        UUID id,
        UUID paymentId,
        String reason,
        String status,
        String assignedTo,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReviewCaseResponse from(ReviewCaseEntity reviewCase) {
        return new ReviewCaseResponse(
                reviewCase.getId(),
                reviewCase.getPaymentId(),
                reviewCase.getReason(),
                reviewCase.getStatus().name(),
                reviewCase.getAssignedTo(),
                reviewCase.getCreatedAt(),
                reviewCase.getUpdatedAt()
        );
    }
}
