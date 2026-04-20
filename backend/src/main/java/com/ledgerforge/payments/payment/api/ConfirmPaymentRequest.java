package com.ledgerforge.payments.payment.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ConfirmPaymentRequest(
        Boolean newDevice,
        @Pattern(regexp = "^[A-Z]{2}$") String ipCountry,
        @Pattern(regexp = "^[A-Z]{2}$") String accountCountry,
        @Min(0) Integer recentDeclines,
        @Min(0) Integer accountAgeMinutes
) {
}
