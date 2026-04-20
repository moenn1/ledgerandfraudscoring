package com.ledgerforge.payments.fraud;

import com.ledgerforge.payments.payment.RiskDecision;

import java.util.List;

public record FraudEvaluation(
        int score,
        RiskDecision decision,
        List<FraudReason> reasons
) {
}
