package com.ledgerforge.payments.payment;

import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.common.web.CorrelationIds;
import com.ledgerforge.payments.ledger.LedgerEntryResponse;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.PaymentIntentResponse;
import com.ledgerforge.payments.payment.api.PaymentRiskResponse;
import com.ledgerforge.payments.payment.api.RefundPaymentRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public PaymentIntentResponse create(@Valid @RequestBody CreatePaymentRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                        HttpServletRequest servletRequest) {
        String idempotencyKey = resolveCreateIdempotencyKey(request.idempotencyKey(), headerKey);
        String correlationId = CorrelationIds.resolve(servletRequest);
        return PaymentIntentResponse.from(paymentService.createWithIdempotency(request, idempotencyKey, correlationId));
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<PaymentIntentResponse> list() {
        return paymentService.list().stream().map(PaymentIntentResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public PaymentIntentResponse get(@PathVariable UUID id) {
        return PaymentIntentResponse.from(paymentService.get(id));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('OPERATOR')")
    public PaymentIntentResponse confirm(@PathVariable UUID id,
                                         @Valid @RequestBody(required = false) ConfirmPaymentRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                         HttpServletRequest servletRequest) {
        return PaymentIntentResponse.from(
                paymentService.confirm(
                        id,
                        request,
                        resolveActionIdempotencyKey("confirm", id, idempotencyKey),
                        CorrelationIds.resolve(servletRequest)
                )
        );
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasRole('OPERATOR')")
    public PaymentIntentResponse capture(@PathVariable UUID id,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                         HttpServletRequest servletRequest) {
        return PaymentIntentResponse.from(
                paymentService.capture(
                        id,
                        resolveActionIdempotencyKey("capture", id, idempotencyKey),
                        CorrelationIds.resolve(servletRequest)
                )
        );
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('OPERATOR')")
    public PaymentIntentResponse refund(@PathVariable UUID id,
                                        @Valid @RequestBody(required = false) RefundPaymentRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        HttpServletRequest servletRequest) {
        RefundPaymentRequest refundRequest = request == null ? new RefundPaymentRequest(null, null, null) : request;
        return PaymentIntentResponse.from(
                paymentService.refund(
                        id,
                        refundRequest,
                        resolveActionIdempotencyKey("refund", id, idempotencyKey),
                        CorrelationIds.resolve(servletRequest)
                )
        );
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OPERATOR')")
    public PaymentIntentResponse cancel(@PathVariable UUID id,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        HttpServletRequest servletRequest) {
        return PaymentIntentResponse.from(
                paymentService.cancel(
                        id,
                        resolveActionIdempotencyKey("cancel", id, idempotencyKey),
                        CorrelationIds.resolve(servletRequest)
                )
        );
    }

    @GetMapping("/{id}/risk")
    @PreAuthorize("hasRole('VIEWER')")
    public PaymentRiskResponse risk(@PathVariable UUID id) {
        return PaymentRiskResponse.from(paymentService.get(id), paymentService.paymentRisk(id));
    }

    @GetMapping("/{id}/ledger")
    @PreAuthorize("hasRole('VIEWER')")
    public List<LedgerEntryResponse> ledger(@PathVariable UUID id) {
        return paymentService.paymentLedger(id).stream().map(LedgerEntryResponse::from).toList();
    }

    private String resolveCreateIdempotencyKey(String bodyKey, String headerKey) {
        if (headerKey != null && !headerKey.isBlank() && bodyKey != null && !bodyKey.isBlank() && !headerKey.equals(bodyKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency key mismatch between header and body");
        }
        String key = bodyKey;
        if (key == null || key.isBlank()) {
            key = headerKey;
        }
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency key is required");
        }
        return key;
    }

    private String resolveActionIdempotencyKey(String action, UUID paymentId, String provided) {
        if (provided == null || provided.isBlank()) {
            return "AUTO-" + action + "-" + paymentId;
        }
        return provided;
    }
}
