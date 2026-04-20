package com.ledgerforge.payments.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentRequest(
        @NotNull UUID payerAccountId,
        @NotNull UUID payeeAccountId,
        @DecimalMin(value = "0.01") BigDecimal amount,
        @Min(1) Long amountCents,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        String idempotencyKey
) {
}
