package com.ledgerforge.payments.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateWebhookEndpointRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 1024) String url,
        @NotEmpty List<@NotBlank @Size(max = 96) String> subscribedEvents,
        Integer maxAttempts,
        @NotBlank @Size(max = 256) String signingSecret
) {
}
