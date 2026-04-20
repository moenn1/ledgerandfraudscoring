package com.ledgerforge.payments.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotBlank String ownerId,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency
) {
}
