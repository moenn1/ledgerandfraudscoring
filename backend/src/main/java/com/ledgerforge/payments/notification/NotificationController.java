package com.ledgerforge.payments.notification;

import com.ledgerforge.payments.common.web.CorrelationIds;
import com.ledgerforge.payments.notification.api.CreateWebhookEndpointRequest;
import com.ledgerforge.payments.notification.api.DispatchWebhookDeliveriesResponse;
import com.ledgerforge.payments.notification.api.WebhookCallbackResponse;
import com.ledgerforge.payments.notification.api.WebhookDeliveryResponse;
import com.ledgerforge.payments.notification.api.WebhookEndpointResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/webhooks")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/endpoints")
    @PreAuthorize("hasRole('ADMIN')")
    public WebhookEndpointResponse createEndpoint(@Valid @RequestBody CreateWebhookEndpointRequest request) {
        return notificationService.createEndpoint(request);
    }

    @GetMapping("/endpoints")
    @PreAuthorize("hasRole('VIEWER')")
    public List<WebhookEndpointResponse> listEndpoints() {
        return notificationService.listEndpoints();
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasRole('VIEWER')")
    public List<WebhookDeliveryResponse> listDeliveries(@RequestParam(required = false) UUID paymentId) {
        return notificationService.listDeliveries(paymentId);
    }

    @PostMapping("/deliveries/dispatch")
    @PreAuthorize("hasRole('ADMIN')")
    public DispatchWebhookDeliveriesResponse dispatchDeliveries(@RequestParam(defaultValue = "25") int limit) {
        return notificationService.dispatchDueDeliveries(limit);
    }

    @PostMapping("/callbacks/{endpointId}")
    public WebhookCallbackResponse acceptCallback(@PathVariable UUID endpointId,
                                                  @RequestHeader("X-LedgerForge-Callback-Id") String callbackId,
                                                  @RequestHeader("X-LedgerForge-Signature") String signature,
                                                  @RequestBody String rawBody,
                                                  HttpServletRequest servletRequest) {
        return notificationService.acceptCallback(
                endpointId,
                callbackId,
                signature,
                CorrelationIds.resolve(servletRequest),
                rawBody == null ? "" : rawBody
        );
    }
}
