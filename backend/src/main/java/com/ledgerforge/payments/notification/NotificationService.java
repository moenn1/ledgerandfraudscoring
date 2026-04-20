package com.ledgerforge.payments.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.common.security.FieldEncryptionService;
import com.ledgerforge.payments.common.security.SensitiveDataMasking;
import com.ledgerforge.payments.notification.api.CreateWebhookEndpointRequest;
import com.ledgerforge.payments.notification.api.DispatchWebhookDeliveriesResponse;
import com.ledgerforge.payments.notification.api.WebhookCallbackRequest;
import com.ledgerforge.payments.notification.api.WebhookCallbackResponse;
import com.ledgerforge.payments.notification.api.WebhookDeliveryResponse;
import com.ledgerforge.payments.notification.api.WebhookEndpointResponse;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.api.PaymentIntentResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationService {

    private static final String SIGNATURE_HEADER = "X-LedgerForge-Signature";

    private final NotificationEndpointRepository endpointRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationCallbackRepository callbackRepository;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final FieldEncryptionService fieldEncryptionService;
    private final Validator validator;
    private final HttpClient httpClient;
    private final boolean directEnqueueEnabled;

    public NotificationService(NotificationEndpointRepository endpointRepository,
                               NotificationDeliveryRepository deliveryRepository,
                               NotificationCallbackRepository callbackRepository,
                               NotificationProperties properties,
                               ObjectMapper objectMapper,
                               AuditService auditService,
                               FieldEncryptionService fieldEncryptionService,
                               Validator validator,
                               @Value("${ledgerforge.kafka.enabled:false}") boolean kafkaEnabled) {
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.callbackRepository = callbackRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.fieldEncryptionService = fieldEncryptionService;
        this.validator = validator;
        this.directEnqueueEnabled = !kafkaEnabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    @Transactional
    public WebhookEndpointResponse createEndpoint(@Valid CreateWebhookEndpointRequest request) {
        validateUrl(request.url());
        List<String> subscribedEvents = normalizeSubscribedEvents(request.subscribedEvents());
        int maxAttempts = request.maxAttempts() == null ? properties.getDefaultMaxAttempts() : request.maxAttempts();
        if (maxAttempts < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maxAttempts must be at least 1");
        }
        String signingSecret = request.signingSecret().trim();

        NotificationEndpointEntity endpoint = new NotificationEndpointEntity();
        endpoint.setName(request.name().trim());
        endpoint.setUrl(request.url().trim());
        endpoint.setSubscribedEventTypes(String.join(",", subscribedEvents));
        endpoint.setSigningSecret(fieldEncryptionService.encrypt(signingSecret));
        endpoint.setActive(true);
        endpoint.setMaxAttempts(maxAttempts);
        return WebhookEndpointResponse.from(endpointRepository.save(endpoint), SensitiveDataMasking.maskSecret(signingSecret));
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpointResponse> listEndpoints() {
        return endpointRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(endpoint -> WebhookEndpointResponse.from(
                        endpoint,
                        SensitiveDataMasking.maskSecret(fieldEncryptionService.decrypt(endpoint.getSigningSecret()))
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> listDeliveries(UUID paymentId) {
        List<NotificationDeliveryEntity> deliveries = paymentId == null
                ? deliveryRepository.findAllByOrderByCreatedAtDesc()
                : deliveryRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId);
        return deliveries.stream().map(WebhookDeliveryResponse::from).toList();
    }

    @Transactional
    public void enqueuePaymentEvent(String eventType,
                                    PaymentIntentEntity payment,
                                    String correlationId,
                                    Map<String, Object> details) {
        if (!directEnqueueEnabled) {
            return;
        }
        enqueuePaymentEventInternal(eventType, payment, correlationId, details);
    }

    @Transactional
    public void enqueuePaymentEventFromBroker(String eventType,
                                              PaymentIntentEntity payment,
                                              String correlationId,
                                              Map<String, Object> details) {
        enqueuePaymentEventInternal(eventType, payment, correlationId, details);
    }

    private void enqueuePaymentEventInternal(String eventType,
                                             PaymentIntentEntity payment,
                                             String correlationId,
                                             Map<String, Object> details) {
        List<NotificationEndpointEntity> endpoints = endpointRepository.findByActiveTrueOrderByCreatedAtAsc();
        if (endpoints.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (NotificationEndpointEntity endpoint : endpoints) {
            if (!matchesSubscription(endpoint.getSubscribedEventTypes(), eventType)) {
                continue;
            }

            NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
            delivery.setId(UUID.randomUUID());
            delivery.setEndpointId(endpoint.getId());
            delivery.setPaymentId(payment.getId());
            delivery.setEventType(eventType);
            delivery.setCorrelationId(correlationId);
            delivery.setStatus(NotificationDeliveryStatus.PENDING);
            delivery.setReceiptStatus(NotificationReceiptStatus.PENDING);
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(now);

            String payloadJson = writeJson(Map.of(
                    "deliveryId", delivery.getId(),
                    "eventType", eventType,
                    "occurredAt", now,
                    "correlationId", correlationId,
                    "payment", PaymentIntentResponse.from(payment),
                    "details", details == null ? Map.of() : details
            ));
            delivery.setPayloadJson(payloadJson);
            delivery.setPayloadHash(sha256(payloadJson));
            deliveryRepository.save(delivery);

            auditService.appendWithActor(
                    "notification.delivery.enqueued",
                    payment.getId(),
                    null,
                    null,
                    correlationId,
                    "notification-endpoint",
                    endpoint.getId().toString(),
                    Map.of(
                            "deliveryId", delivery.getId(),
                            "eventType", eventType,
                            "status", delivery.getStatus().name()
                    )
            );
        }
    }

    public DispatchWebhookDeliveriesResponse dispatchDueDeliveries(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, properties.getMaxBatchSize()));
        Instant now = Instant.now();
        List<NotificationDeliveryEntity> dueDeliveries = deliveryRepository
                .findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                        List.of(NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.RETRY_PENDING),
                        now,
                        PageRequest.of(0, limit)
                );

        int succeeded = 0;
        int retrying = 0;
        int failed = 0;
        List<WebhookDeliveryResponse> responses = new ArrayList<>();
        for (NotificationDeliveryEntity delivery : dueDeliveries) {
            NotificationDeliveryEntity updated = dispatchSingleDelivery(delivery, now);
            responses.add(WebhookDeliveryResponse.from(updated));
            switch (updated.getStatus()) {
                case SUCCEEDED -> succeeded++;
                case RETRY_PENDING, PENDING -> retrying++;
                case FAILED -> failed++;
            }
        }

        return new DispatchWebhookDeliveriesResponse(
                now,
                responses.size(),
                succeeded,
                retrying,
                failed,
                responses
        );
    }

    @Transactional
    public WebhookCallbackResponse acceptCallback(UUID endpointId,
                                                  String callbackId,
                                                  String signature,
                                                  String correlationId,
                                                  String rawBody) {
        NotificationEndpointEntity endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Webhook endpoint not found: " + endpointId));

        verifySignature(resolveSigningSecret(endpoint, true), rawBody, signature);
        WebhookCallbackRequest request = readCallbackRequest(rawBody);

        String fingerprint = sha256(rawBody);
        Optional<NotificationCallbackEntity> existing = callbackRepository.findByEndpointIdAndCallbackId(endpointId, callbackId);
        if (existing.isPresent()) {
            NotificationCallbackEntity callback = existing.get();
            if (!callback.getFingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "Callback id reused with different payload");
            }
            return buildCallbackResponse(callback, true);
        }

        NotificationCallbackEntity callback = new NotificationCallbackEntity();
        callback.setEndpointId(endpointId);
        callback.setCallbackId(callbackId);
        callback.setFingerprint(fingerprint);
        callback.setPayloadJson(rawBody);
        callback.setCorrelationId(correlationId);

        NotificationDeliveryEntity delivery = deliveryRepository.findByIdAndEndpointId(request.deliveryId(), endpointId).orElse(null);
        if (delivery == null) {
            callback.setDeliveryId(request.deliveryId());
            callback.setStatus(NotificationCallbackStatus.UNMATCHED);
            callback.setReason("Delivery not found for endpoint");
            NotificationCallbackEntity saved = callbackRepository.save(callback);
            auditService.appendWithActor(
                    "notification.callback.unmatched",
                    null,
                    null,
                    null,
                    correlationId,
                    "notification-endpoint",
                    endpointId.toString(),
                    Map.of(
                            "callbackId", callbackId,
                            "deliveryId", request.deliveryId()
                    )
            );
            return new WebhookCallbackResponse(
                    saved.getCallbackId(),
                    saved.getDeliveryId(),
                    false,
                    false,
                    null,
                    null,
                    saved.getCreatedAt(),
                    null
            );
        }

        NotificationReceiptStatus receiptStatus = parseReceiptStatus(request.status());
        Instant now = Instant.now();
        delivery.setReceiptStatus(receiptStatus);
        delivery.setCallbackReceivedAt(now);
        delivery.setCallbackReason(normalizeReason(request.reason()));

        if (receiptStatus == NotificationReceiptStatus.ACKNOWLEDGED) {
            if (delivery.getStatus() != NotificationDeliveryStatus.FAILED) {
                delivery.setStatus(NotificationDeliveryStatus.SUCCEEDED);
            }
            delivery.setNextAttemptAt(null);
        } else if (delivery.getAttemptCount() < endpoint.getMaxAttempts()) {
            delivery.setStatus(NotificationDeliveryStatus.RETRY_PENDING);
            delivery.setNextAttemptAt(now.plusSeconds(retryDelaySeconds(Math.max(1, delivery.getAttemptCount()))));
        } else {
            delivery.setStatus(NotificationDeliveryStatus.FAILED);
            delivery.setNextAttemptAt(null);
        }
        deliveryRepository.save(delivery);

        callback.setDeliveryId(delivery.getId());
        callback.setPaymentId(delivery.getPaymentId());
        callback.setEventType(delivery.getEventType());
        callback.setStatus(NotificationCallbackStatus.APPLIED);
        callback.setReason(normalizeReason(request.reason()));
        NotificationCallbackEntity saved = callbackRepository.save(callback);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("callbackId", callbackId);
        auditDetails.put("deliveryId", delivery.getId());
        auditDetails.put("receiptStatus", receiptStatus.name());
        auditDetails.put("deliveryStatus", delivery.getStatus().name());
        if (delivery.getNextAttemptAt() != null) {
            auditDetails.put("nextAttemptAt", delivery.getNextAttemptAt());
        }
        if (delivery.getCallbackReason() != null && !delivery.getCallbackReason().isBlank()) {
            auditDetails.put("reason", delivery.getCallbackReason());
        }
        auditService.appendWithActor(
                receiptStatus == NotificationReceiptStatus.ACKNOWLEDGED
                        ? "notification.callback.acknowledged"
                        : "notification.callback.rejected",
                delivery.getPaymentId(),
                null,
                null,
                correlationId,
                "notification-endpoint",
                endpointId.toString(),
                auditDetails
        );

        return new WebhookCallbackResponse(
                saved.getCallbackId(),
                delivery.getId(),
                false,
                true,
                delivery.getStatus().name(),
                delivery.getReceiptStatus().name(),
                saved.getCreatedAt(),
                delivery.getNextAttemptAt()
        );
    }

    private NotificationDeliveryEntity dispatchSingleDelivery(NotificationDeliveryEntity delivery, Instant dispatchStartedAt) {
        NotificationEndpointEntity endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null || !endpoint.isActive()) {
            delivery.setStatus(NotificationDeliveryStatus.FAILED);
            delivery.setNextAttemptAt(null);
            delivery.setLastError("Endpoint is missing or inactive");
            return deliveryRepository.save(delivery);
        }

        if (delivery.getStatus() == NotificationDeliveryStatus.RETRY_PENDING
                && delivery.getReceiptStatus() == NotificationReceiptStatus.REJECTED) {
            delivery.setReceiptStatus(NotificationReceiptStatus.PENDING);
            delivery.setCallbackReason(null);
            delivery.setCallbackReceivedAt(null);
        }

        long attemptNumber = (long) delivery.getAttemptCount() + 1;
        String signature = createSignature(resolveSigningSecret(endpoint, true), delivery.getPayloadJson(), dispatchStartedAt);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.getUrl()))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-LedgerForge-Delivery-Id", delivery.getId().toString())
                .header("X-LedgerForge-Event-Type", delivery.getEventType())
                .header("X-LedgerForge-Attempt", Long.toString(attemptNumber))
                .header(SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayloadJson(), StandardCharsets.UTF_8))
                .build();

        delivery.setAttemptCount((int) attemptNumber);
        delivery.setLastAttemptAt(dispatchStartedAt);
        delivery.setLastSignature(signature);
        delivery.setLastError(null);

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            delivery.setLastResponseStatus(response.statusCode());
            delivery.setLastResponseBody(truncate(response.body(), properties.getMaxResponseBodyChars()));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.setStatus(NotificationDeliveryStatus.SUCCEEDED);
                delivery.setNextAttemptAt(null);
                NotificationDeliveryEntity saved = deliveryRepository.save(delivery);
                auditService.appendWithActor(
                        "notification.delivery.succeeded",
                        saved.getPaymentId(),
                        null,
                        null,
                        saved.getCorrelationId(),
                        "notification-endpoint",
                        endpoint.getId().toString(),
                        Map.of(
                                "deliveryId", saved.getId(),
                                "eventType", saved.getEventType(),
                                "attemptCount", saved.getAttemptCount()
                        )
                );
                return saved;
            }
            return saveRetryOrFailure(delivery, endpoint, "HTTP " + response.statusCode());
        } catch (IOException ex) {
            return saveRetryOrFailure(delivery, endpoint, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return saveRetryOrFailure(delivery, endpoint, "Dispatch interrupted");
        }
    }

    private NotificationDeliveryEntity saveRetryOrFailure(NotificationDeliveryEntity delivery,
                                                          NotificationEndpointEntity endpoint,
                                                          String error) {
        delivery.setLastError(truncate(error, 512));
        if (delivery.getAttemptCount() < endpoint.getMaxAttempts()) {
            delivery.setStatus(NotificationDeliveryStatus.RETRY_PENDING);
            delivery.setNextAttemptAt(Instant.now().plusSeconds(retryDelaySeconds(delivery.getAttemptCount())));
            NotificationDeliveryEntity saved = deliveryRepository.save(delivery);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("deliveryId", saved.getId());
            details.put("eventType", saved.getEventType());
            details.put("attemptCount", saved.getAttemptCount());
            details.put("nextAttemptAt", saved.getNextAttemptAt());
            if (saved.getLastError() != null) {
                details.put("error", saved.getLastError());
            }
            auditService.appendWithActor(
                    "notification.delivery.retry_scheduled",
                    saved.getPaymentId(),
                    null,
                    null,
                    saved.getCorrelationId(),
                    "notification-endpoint",
                    endpoint.getId().toString(),
                    details
            );
            return saved;
        }

        delivery.setStatus(NotificationDeliveryStatus.FAILED);
        delivery.setNextAttemptAt(null);
        NotificationDeliveryEntity saved = deliveryRepository.save(delivery);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deliveryId", saved.getId());
        details.put("eventType", saved.getEventType());
        details.put("attemptCount", saved.getAttemptCount());
        if (saved.getLastError() != null) {
            details.put("error", saved.getLastError());
        }
        auditService.appendWithActor(
                "notification.delivery.failed",
                saved.getPaymentId(),
                null,
                null,
                saved.getCorrelationId(),
                "notification-endpoint",
                endpoint.getId().toString(),
                details
        );
        return saved;
    }

    private WebhookCallbackResponse buildCallbackResponse(NotificationCallbackEntity callback, boolean duplicate) {
        NotificationDeliveryEntity delivery = callback.getDeliveryId() == null
                ? null
                : deliveryRepository.findById(callback.getDeliveryId()).orElse(null);
        return new WebhookCallbackResponse(
                callback.getCallbackId(),
                callback.getDeliveryId(),
                duplicate,
                callback.getStatus() == NotificationCallbackStatus.APPLIED,
                delivery == null ? null : delivery.getStatus().name(),
                delivery == null ? null : delivery.getReceiptStatus().name(),
                callback.getCreatedAt(),
                delivery == null ? null : delivery.getNextAttemptAt()
        );
    }

    private WebhookCallbackRequest readCallbackRequest(String rawBody) {
        try {
            WebhookCallbackRequest request = objectMapper.readValue(rawBody, WebhookCallbackRequest.class);
            validator.validate(request).stream().findFirst().ifPresent(violation -> {
                throw new ApiException(HttpStatus.BAD_REQUEST, violation.getMessage());
            });
            return request;
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid callback JSON payload");
        }
    }

    private NotificationReceiptStatus parseReceiptStatus(String rawStatus) {
        String normalized = rawStatus == null ? "" : rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ACKNOWLEDGED" -> NotificationReceiptStatus.ACKNOWLEDGED;
            case "REJECTED" -> NotificationReceiptStatus.REJECTED;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported callback status: " + rawStatus);
        };
    }

    private List<String> normalizeSubscribedEvents(List<String> subscribedEvents) {
        List<String> normalized = subscribedEvents.stream()
                .map(event -> event == null ? "" : event.trim())
                .filter(event -> !event.isBlank())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At least one subscribed event is required");
        }
        return normalized;
    }

    private boolean matchesSubscription(String subscribedEventTypes, String eventType) {
        for (String pattern : subscribedEventTypes.split(",")) {
            String trimmed = pattern.trim();
            if (trimmed.equals("*") || trimmed.equals(eventType)) {
                return true;
            }
            if (trimmed.endsWith("*") && eventType.startsWith(trimmed.substring(0, trimmed.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private void validateUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Webhook endpoint URL must use http or https");
            }
        } catch (URISyntaxException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Webhook endpoint URL is invalid");
        }
    }

    private void verifySignature(String secret, String rawBody, String signatureHeader) {
        SignatureParts parts = parseSignatureHeader(signatureHeader);
        long nowSeconds = Instant.now().getEpochSecond();
        if (Math.abs(nowSeconds - parts.timestamp()) > properties.getCallbackSkewSeconds()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Callback signature timestamp is outside the allowed skew");
        }
        String expected = createSignature(secret, rawBody, Instant.ofEpochSecond(parts.timestamp()));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Callback signature is invalid");
        }
    }

    private SignatureParts parseSignatureHeader(String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Callback signature is required");
        }
        String[] parts = signatureHeader.split(",");
        String timestamp = null;
        String digest = null;
        for (String part : parts) {
            String[] tokens = part.trim().split("=", 2);
            if (tokens.length != 2) {
                continue;
            }
            if (tokens[0].equals("t")) {
                timestamp = tokens[1];
            } else if (tokens[0].equals("v1")) {
                digest = tokens[1];
            }
        }
        if (timestamp == null || digest == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Callback signature is malformed");
        }
        try {
            return new SignatureParts(Long.parseLong(timestamp), digest);
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Callback signature timestamp is invalid");
        }
    }

    private String createSignature(String secret, String payload, Instant timestamp) {
        String digest = sha256Hmac(secret, timestamp.getEpochSecond() + "." + payload);
        return "t=" + timestamp.getEpochSecond() + ",v1=" + digest;
    }

    private String resolveSigningSecret(NotificationEndpointEntity endpoint, boolean migratePlaintext) {
        String storedValue = endpoint.getSigningSecret();
        String signingSecret = fieldEncryptionService.decrypt(storedValue);
        if (migratePlaintext && storedValue != null && !storedValue.isBlank() && !fieldEncryptionService.isEncrypted(storedValue)) {
            endpoint.setSigningSecret(fieldEncryptionService.encrypt(signingSecret));
            endpointRepository.save(endpoint);
        }
        return signingSecret;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute webhook payload hash", ex);
        }
    }

    private String sha256Hmac(String secret, String value) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute webhook signature", ex);
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize notification payload", ex);
        }
    }

    private long retryDelaySeconds(int attemptCount) {
        long multiplier = 1L << Math.max(0, Math.min(attemptCount - 1, 5));
        return (long) properties.getBaseRetryDelaySeconds() * multiplier;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return truncate(reason.trim(), 512);
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private record SignatureParts(long timestamp, String digest) {
    }
}
