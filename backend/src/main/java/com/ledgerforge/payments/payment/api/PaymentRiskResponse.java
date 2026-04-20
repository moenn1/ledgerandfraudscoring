package com.ledgerforge.payments.payment.api;

import com.ledgerforge.payments.fraud.FraudEvaluation;
import com.ledgerforge.payments.payment.PaymentIntentEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentRiskResponse(
        UUID paymentId,
        String paymentStatus,
        int riskScore,
        String decision,
        List<FraudReasonResponse> reasons,
        Instant updatedAt
) {
    public static PaymentRiskResponse from(PaymentIntentEntity payment, FraudEvaluation evaluation) {
        return new PaymentRiskResponse(
                payment.getId(),
                payment.getStatus().name(),
                evaluation.score(),
                evaluation.decision().name(),
                evaluation.reasons().stream().map(FraudReasonResponse::from).toList(),
                payment.getUpdatedAt()
        );
    }
}
