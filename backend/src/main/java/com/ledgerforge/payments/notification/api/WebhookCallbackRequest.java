package com.ledgerforge.payments.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record WebhookCallbackRequest(
        @NotNull UUID deliveryId,
        @NotBlank @Size(max = 24) String status,
        @Size(max = 512) String reason
) {
}
