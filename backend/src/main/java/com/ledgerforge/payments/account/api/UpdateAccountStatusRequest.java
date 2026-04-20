package com.ledgerforge.payments.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAccountStatusRequest(
        @NotBlank String status,
        @Size(max = 512) String reason
) {
}
