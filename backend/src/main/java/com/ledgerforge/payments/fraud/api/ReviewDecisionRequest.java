package com.ledgerforge.payments.fraud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
        @NotNull ReviewDecision decision,
        @NotBlank String actor,
        @Size(max = 512) String note
) {
    public enum ReviewDecision {
        APPROVE,
        REJECT
    }
}
