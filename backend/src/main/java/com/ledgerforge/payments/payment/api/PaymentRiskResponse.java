package com.ledgerforge.payments.payment.api;

import com.ledgerforge.payments.fraud.FraudEvaluation;
import com.ledgerforge.payments.payment.PaymentIntentEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentRiskResponse(
        UUID paymentId,
        Integer riskScore,
        String riskDecision,
        String failureReason,
        List<FraudReasonResponse> reasons,
        String paymentStatus,
        Instant updatedAt
) {
    public static PaymentRiskResponse from(PaymentIntentEntity payment, FraudEvaluation evaluation) {
        return new PaymentRiskResponse(
                payment.getId(),
                evaluation.score(),
                evaluation.decision().name(),
                payment.getFailureReason(),
                evaluation.reasons().stream().map(FraudReasonResponse::from).toList(),
                payment.getStatus().name(),
                payment.getUpdatedAt()
        );
    }

    public static PaymentRiskResponse from(PaymentIntentEntity payment) {
        return new PaymentRiskResponse(
                payment.getId(),
                payment.getRiskScore(),
                payment.getRiskDecision() == null ? null : payment.getRiskDecision().name(),
                payment.getFailureReason(),
                List.of(),
                payment.getStatus().name(),
                payment.getUpdatedAt()
        );
    }
}
