package com.ledgerforge.payments.fraud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
        @NotNull ReviewDecision decision,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._@:/-]+") String actor,
        @Size(max = 512) String note
) {
    public enum ReviewDecision {
        APPROVE,
        REJECT
    }
}
