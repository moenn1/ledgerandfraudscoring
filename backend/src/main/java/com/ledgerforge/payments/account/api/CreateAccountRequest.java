package com.ledgerforge.payments.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record CreateAccountRequest(
        @NotBlank String ownerId,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        List<@NotBlank @Pattern(regexp = "^[A-Z]{3}$") String> supportedCurrencies
) {
    public CreateAccountRequest(String ownerId, String currency) {
        this(ownerId, currency, null);
    }
}
