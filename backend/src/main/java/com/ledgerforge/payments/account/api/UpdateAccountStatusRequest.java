package com.ledgerforge.payments.account.api;

import jakarta.validation.constraints.NotBlank;

public record UpdateAccountStatusRequest(
        @NotBlank String status,
        String reason
) {
}
