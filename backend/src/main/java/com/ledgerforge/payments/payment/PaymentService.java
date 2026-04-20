package com.ledgerforge.payments.payment;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.fraud.FraudEvaluation;
import com.ledgerforge.payments.fraud.FraudReason;
import com.ledgerforge.payments.fraud.FraudScoringService;
import com.ledgerforge.payments.fraud.ManualReviewService;
import com.ledgerforge.payments.idempotency.IdempotencyService;
import com.ledgerforge.payments.ledger.CreateJournalRequest;
import com.ledgerforge.payments.ledger.CreateLedgerLegRequest;
import com.ledgerforge.payments.ledger.JournalResponse;
import com.ledgerforge.payments.ledger.JournalType;
import com.ledgerforge.payments.ledger.LedgerEntryEntity;
import com.ledgerforge.payments.ledger.LedgerService;
import com.ledgerforge.payments.notification.NotificationService;
import com.ledgerforge.payments.outbox.OutboxService;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.PaymentAdjustmentRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

@Service
public class PaymentService {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.03");

    private final PaymentIntentRepository paymentRepository;
    private final PaymentAdjustmentRepository paymentAdjustmentRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final FraudScoringService fraudScoringService;
    private final ManualReviewService manualReviewService;
    private final NotificationService notificationService;
    private final OutboxService outboxService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentService(PaymentIntentRepository paymentRepository,
                          PaymentAdjustmentRepository paymentAdjustmentRepository,
                          AccountService accountService,
                          LedgerService ledgerService,
                          IdempotencyService idempotencyService,
                          AuditService auditService,
                          FraudScoringService fraudScoringService,
                          ManualReviewService manualReviewService,
                          NotificationService notificationService,
                          OutboxService outboxService,
                          PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.paymentAdjustmentRepository = paymentAdjustmentRepository;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.fraudScoringService = fraudScoringService;
        this.manualReviewService = manualReviewService;
        this.notificationService = notificationService;
        this.outboxService = outboxService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public PaymentIntentEntity create(CreatePaymentRequest request, String idempotencyKey, String correlationId) {
        BigDecimal amount = resolveAmount(request.amount(), request.amountCents());
        String currency = normalizeCurrency(request.currency());

        paymentRepository.findByIdempotencyKey(idempotencyKey).ifPresent(existing -> {
            String existingFingerprint = createFingerprint(existing);
            String requestedFingerprint = createFingerprint(request.payerAccountId(), request.payeeAccountId(), amount, currency);
            if (!existingFingerprint.equals(requestedFingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "Idempotency key reused with different create payload");
            }
            throw new ExistingPaymentException(existing);
        });

        validatePaymentAccounts(request.payerAccountId(), request.payeeAccountId(), currency);

        PaymentIntentEntity payment = new PaymentIntentEntity();
        payment.setPayerAccountId(request.payerAccountId());
        payment.setPayeeAccountId(request.payeeAccountId());
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setIdempotencyKey(idempotencyKey);
        payment.setStatus(PaymentStatus.CREATED);
        payment.setFailureReason(null);

        PaymentIntentEntity saved = paymentRepository.saveAndFlush(payment);
        auditService.append(
                "payment.created",
                saved.getId(),
                null,
                null,
                correlationId,
                Map.of(
                        "status", saved.getStatus().name(),
                        "amount", saved.getAmount(),
                        "currency", saved.getCurrency()
                )
        );
        publishPaymentEvent("payment.created", saved, correlationId, idempotencyKey, Map.of());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PaymentIntentEntity> list() {
        return paymentRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public PaymentIntentEntity get(UUID id) {
        return getOrFail(id);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryEntity> paymentLedger(UUID paymentId) {
        return ledgerService.listByReferencePrefix(referencePrefix(paymentId));
    }

    @Transactional(readOnly = true)
    public List<PaymentAdjustmentEntity> paymentAdjustments(UUID paymentId) {
        getOrFail(paymentId);
        return paymentAdjustmentRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    @Transactional(readOnly = true)
    public FraudEvaluation paymentRisk(UUID paymentId) {
        PaymentIntentEntity payment = getOrFail(paymentId);
        if (payment.getRiskScore() == null || payment.getRiskDecision() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Risk has not been evaluated for payment: " + paymentId);
        }
        return fraudScoringService.loadPersistedEvaluation(
                payment.getId(),
                payment.getRiskScore(),
                payment.getRiskDecision()
        );
    }

    @Transactional
    public PaymentIntentEntity confirm(UUID paymentId,
                                       ConfirmPaymentRequest request,
                                       String idempotencyKey,
                                       String correlationId) {
        ConfirmPaymentRequest normalizedRequest = request == null
                ? new ConfirmPaymentRequest(null, null, null, null, null)
                : request;

        String scope = mutationScope("confirm", paymentId);
        String fingerprint = confirmFingerprint(normalizedRequest);
        if (idempotencyService.isReplay(scope, idempotencyKey, fingerprint)) {
            return getOrFail(paymentId);
        }

        PaymentIntentEntity payment = getOrFail(paymentId);
        ensureStatus(payment, PaymentStatus.CREATED, "confirm");

        AccountEntity payer = accountService.getOrFail(payment.getPayerAccountId());
        AccountEntity payee = accountService.getOrFail(payment.getPayeeAccountId());
        accountService.requirePaymentParticipationAllowed(payer, "payer");
        accountService.requirePaymentParticipationAllowed(payee, "payee");

        payment.setStatus(PaymentStatus.VALIDATED);
        payment.setStatus(PaymentStatus.RISK_SCORING);

        FraudEvaluation evaluation = fraudScoringService.score(payment, normalizedRequest, payer.getCreatedAt());
        payment.setRiskDecision(evaluation.decision());
        payment.setRiskScore(evaluation.score());

        if (evaluation.decision() == RiskDecision.REJECT) {
            payment.setStatus(PaymentStatus.REJECTED);
            payment.setFailureReason(riskReasonSummary(evaluation.reasons()));
            PaymentIntentEntity saved = paymentRepository.save(payment);
            recordMutation(scope, idempotencyKey, fingerprint, saved);
            auditService.append(
                    "payment.rejected",
                    saved.getId(),
                    saved.getPayerAccountId(),
                    null,
                    correlationId,
                    Map.of(
                            "riskScore", saved.getRiskScore(),
                            "riskDecision", saved.getRiskDecision().name(),
                            "reasonCodes", evaluation.reasons().stream().map(FraudReason::code).toList()
                    )
            );
            publishPaymentEvent(
                    "payment.rejected",
                    saved,
                    correlationId,
                    idempotencyKey,
                    Map.of("reasonCodes", evaluation.reasons().stream().map(FraudReason::code).toList())
            );
            return saved;
        }

        if (evaluation.decision() == RiskDecision.REVIEW) {
            payment.setStatus(PaymentStatus.RISK_SCORING);
            payment.setFailureReason(reviewFailureReason(evaluation.reasons()));
            PaymentIntentEntity saved = paymentRepository.save(payment);
            manualReviewService.openCaseIfAbsent(saved, evaluation.reasons(), correlationId);
            recordMutation(scope, idempotencyKey, fingerprint, saved);
            auditService.append(
                    "payment.review_required",
                    saved.getId(),
                    saved.getPayerAccountId(),
                    null,
                    correlationId,
                    Map.of(
                            "riskScore", saved.getRiskScore(),
                            "riskDecision", saved.getRiskDecision().name(),
                            "reasonCodes", evaluation.reasons().stream().map(FraudReason::code).toList()
                    )
            );
            publishPaymentEvent(
                    "payment.review_required",
                    saved,
                    correlationId,
                    idempotencyKey,
                    Map.of("reasonCodes", evaluation.reasons().stream().map(FraudReason::code).toList())
            );
            return saved;
        }

        payment.setStatus(PaymentStatus.APPROVED);
        payment.setFailureReason(null);

        JournalResponse reserveJournal = reserveFunds(payment, referenceId(payment.getId(), "reserve"), correlationId);

        payment.setStatus(PaymentStatus.RESERVED);
        PaymentIntentEntity saved = paymentRepository.save(payment);
        recordMutation(scope, idempotencyKey, fingerprint, saved);

        auditService.append(
                "payment.reserved",
                saved.getId(),
                saved.getPayerAccountId(),
                reserveJournal.id(),
                correlationId,
                Map.of(
                        "status", saved.getStatus().name(),
                        "riskScore", saved.getRiskScore(),
                        "riskDecision", saved.getRiskDecision().name(),
                        "reasonCodes", evaluation.reasons().stream().map(FraudReason::code).toList()
                )
        );
        publishPaymentEvent(
                "payment.reserved",
                saved,
                correlationId,
                idempotencyKey,
                Map.of(
                        "journalId", reserveJournal.id(),
                        "reasonCodes", evaluation.reasons().stream().map(FraudReason::code).toList()
                )
        );
        return saved;
    }

    @Transactional
    public PaymentIntentEntity capture(UUID paymentId, String idempotencyKey, String correlationId) {
        String scope = mutationScope("capture", paymentId);
        String fingerprint = "capture";
        if (idempotencyService.isReplay(scope, idempotencyKey, fingerprint)) {
            return getOrFail(paymentId);
        }

        PaymentIntentEntity payment = getOrFail(paymentId);
        if (payment.getStatus() == PaymentStatus.CAPTURED || payment.getStatus() == PaymentStatus.SETTLED) {
            recordMutation(scope, idempotencyKey, fingerprint, payment);
            return payment;
        }
        ensureStatus(payment, PaymentStatus.RESERVED, "capture");
        accountService.requirePaymentParticipationAllowed(accountService.getOrFail(payment.getPayerAccountId()), "payer");
        accountService.requirePaymentParticipationAllowed(accountService.getOrFail(payment.getPayeeAccountId()), "payee");

        AccountEntity holdingAccount = accountService.getSystemHoldingAccount(payment.getCurrency());
        AccountEntity revenueAccount = accountService.getSystemRevenueAccount(payment.getCurrency());

        BigDecimal fee = calculateFee(payment.getAmount());
        BigDecimal net = payment.getAmount().subtract(fee);

        JournalResponse captureJournal = ledgerService.createJournal(new CreateJournalRequest(
                JournalType.CAPTURE,
                referenceId(payment.getId(), "capture"),
                List.of(
                        new CreateLedgerLegRequest(holdingAccount.getId(), com.ledgerforge.payments.ledger.LedgerDirection.DEBIT, payment.getAmount(), payment.getCurrency()),
                        new CreateLedgerLegRequest(payment.getPayeeAccountId(), com.ledgerforge.payments.ledger.LedgerDirection.CREDIT, net, payment.getCurrency()),
                        new CreateLedgerLegRequest(revenueAccount.getId(), com.ledgerforge.payments.ledger.LedgerDirection.CREDIT, fee, payment.getCurrency())
                )
        ), correlationId);

        Instant settlementScheduledFor = nextSettlementCutoff(Instant.now());
        payment.setSettlementScheduledFor(settlementScheduledFor);
        payment.setSettledAt(null);
        payment.setSettlementBatchId(null);
        payment.setStatus(PaymentStatus.CAPTURED);
        PaymentIntentEntity saved = paymentRepository.save(payment);
        recordMutation(scope, idempotencyKey, fingerprint, saved);

        auditService.append(
                "payment.captured",
                saved.getId(),
                saved.getPayerAccountId(),
                captureJournal.id(),
                correlationId,
                Map.of(
                        "status", saved.getStatus().name(),
                        "fee", fee,
                        "settlementScheduledFor", settlementScheduledFor
                )
        );
        publishPaymentEvent(
                "payment.captured",
                saved,
                correlationId,
                idempotencyKey,
                Map.of(
                        "journalId", captureJournal.id(),
                        "fee", fee,
                        "settlementScheduledFor", settlementScheduledFor
                )
        );
        return saved;
    }

    @Transactional
    public PaymentIntentEntity refund(UUID paymentId,
                                      PaymentAdjustmentRequest request,
                                      String idempotencyKey,
                                      String correlationId) {
        BigDecimal requestedAmount = resolveOptionalAmount(request.amount(), request.amountCents());
        String fingerprint = "refund:" + (requestedAmount == null ? "FULL" : requestedAmount.toPlainString()) + ":" + nullToBlank(request.reason());
        String scope = mutationScope("refund", paymentId);
        if (idempotencyService.isReplay(scope, idempotencyKey, fingerprint)) {
            return getOrFail(paymentId);
        }

        PaymentIntentEntity payment = getOrFail(paymentId);
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            recordMutation(scope, idempotencyKey, fingerprint, payment);
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.SETTLED) {
            throw new ApiException(HttpStatus.CONFLICT, "Refund only allowed after capture/settlement");
        }

        BigDecimal fee = calculateFee(payment.getAmount());
        requireFullAmountOrDefault(requestedAmount, payment.getAmount(), "refund");

        JournalResponse refundJournal = postCapturedFundsAdjustment(
                payment,
                JournalType.REFUND,
                referenceId(payment.getId(), "refund"),
                fee,
                correlationId
        );

        payment.setStatus(PaymentStatus.REFUNDED);
        PaymentIntentEntity saved = paymentRepository.save(payment);
        appendAdjustment(saved, PaymentAdjustmentType.REFUND, payment.getAmount(), fee, request.reason(), refundJournal, correlationId, idempotencyKey);
        recordMutation(scope, idempotencyKey, fingerprint, saved);

        auditService.append(
                "payment.refunded",
                saved.getId(),
                saved.getPayerAccountId(),
                refundJournal.id(),
                correlationId,
                Map.of("status", saved.getStatus().name(), "reason", nullToBlank(request.reason()))
        );
        publishPaymentEvent(
                "payment.refunded",
                saved,
                correlationId,
                idempotencyKey,
                Map.of(
                        "journalId", refundJournal.id(),
                        "fee", fee,
                        "reason", nullToBlank(request.reason())
                )
        );
        return saved;
    }

    @Transactional
    public PaymentIntentEntity reverse(UUID paymentId,
                                       PaymentAdjustmentRequest request,
                                       String idempotencyKey,
                                       String correlationId) {
        BigDecimal requestedAmount = resolveOptionalAmount(request.amount(), request.amountCents());
        String fingerprint = "reverse:" + (requestedAmount == null ? "FULL" : requestedAmount.toPlainString()) + ":" + nullToBlank(request.reason());
        String scope = mutationScope("reverse", paymentId);
        if (idempotencyService.isReplay(scope, idempotencyKey, fingerprint)) {
            return getOrFail(paymentId);
        }

        PaymentIntentEntity payment = getOrFail(paymentId);
        if (payment.getStatus() == PaymentStatus.REVERSED) {
            recordMutation(scope, idempotencyKey, fingerprint, payment);
            return payment;
        }

        JournalResponse reversalJournal;
        BigDecimal feeAmount = BigDecimal.ZERO;
        if (payment.getStatus() == PaymentStatus.RESERVED) {
            requireFullAmountOrDefault(requestedAmount, payment.getAmount(), "reverse");
            reversalJournal = releaseReservedFunds(payment, referenceId(payment.getId(), "reverse"), correlationId);
        } else if (payment.getStatus() == PaymentStatus.CAPTURED || payment.getStatus() == PaymentStatus.SETTLED) {
            requireFullAmountOrDefault(requestedAmount, payment.getAmount(), "reverse");
            feeAmount = calculateFee(payment.getAmount());
            reversalJournal = postCapturedFundsAdjustment(
                    payment,
                    JournalType.REVERSAL,
                    referenceId(payment.getId(), "reverse"),
                    feeAmount,
                    correlationId
            );
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "Reverse only allowed for reserved/captured/settled payments");
        }

        payment.setStatus(PaymentStatus.REVERSED);
        payment.setFailureReason(null);
        PaymentIntentEntity saved = paymentRepository.save(payment);
        appendAdjustment(saved, PaymentAdjustmentType.REVERSAL, payment.getAmount(), feeAmount, request.reason(), reversalJournal, correlationId, idempotencyKey);
        recordMutation(scope, idempotencyKey, fingerprint, saved);

        auditService.append(
                "payment.reversed",
                saved.getId(),
                saved.getPayerAccountId(),
                reversalJournal.id(),
                correlationId,
                Map.of(
                        "status", saved.getStatus().name(),
                        "reason", nullToBlank(request.reason())
                )
        );
        publishPaymentEvent(
                "payment.reversed",
                saved,
                correlationId,
                idempotencyKey,
                Map.of(
                        "journalId", reversalJournal.id(),
                        "fee", feeAmount,
                        "reason", nullToBlank(request.reason())
                )
        );
        return saved;
    }

    @Transactional
    public PaymentIntentEntity chargeback(UUID paymentId,
                                          PaymentAdjustmentRequest request,
                                          String idempotencyKey,
                                          String correlationId) {
        BigDecimal requestedAmount = resolveOptionalAmount(request.amount(), request.amountCents());
        String fingerprint = "chargeback:" + (requestedAmount == null ? "FULL" : requestedAmount.toPlainString()) + ":" + nullToBlank(request.reason());
        String scope = mutationScope("chargeback", paymentId);
        if (idempotencyService.isReplay(scope, idempotencyKey, fingerprint)) {
            return getOrFail(paymentId);
        }

        PaymentIntentEntity payment = getOrFail(paymentId);
        if (payment.getStatus() == PaymentStatus.CHARGEBACK) {
            recordMutation(scope, idempotencyKey, fingerprint, payment);
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.SETTLED) {
            throw new ApiException(HttpStatus.CONFLICT, "Chargeback only allowed after capture/settlement");
        }

        BigDecimal fee = calculateFee(payment.getAmount());
        requireFullAmountOrDefault(requestedAmount, payment.getAmount(), "chargeback");

        JournalResponse chargebackJournal = postCapturedFundsAdjustment(
                payment,
                JournalType.CHARGEBACK,
                referenceId(payment.getId(), "chargeback"),
                fee,
                correlationId
        );

        payment.setStatus(PaymentStatus.CHARGEBACK);
        payment.setFailureReason(null);
        PaymentIntentEntity saved = paymentRepository.save(payment);
        appendAdjustment(saved, PaymentAdjustmentType.CHARGEBACK, payment.getAmount(), fee, request.reason(), chargebackJournal, correlationId, idempotencyKey);
        recordMutation(scope, idempotencyKey, fingerprint, saved);

        auditService.append(
                "payment.charged_back",
                saved.getId(),
                saved.getPayerAccountId(),
                chargebackJournal.id(),
                correlationId,
                Map.of(
                        "status", saved.getStatus().name(),
                        "reason", nullToBlank(request.reason())
                )
        );
        publishPaymentEvent(
                "payment.charged_back",
                saved,
                correlationId,
                idempotencyKey,
                Map.of(
                        "journalId", chargebackJournal.id(),
                        "fee", fee,
                        "reason", nullToBlank(request.reason())
                )
        );
        return saved;
    }

    @Transactional
    public PaymentIntentEntity cancel(UUID paymentId, String idempotencyKey, String correlationId) {
        String scope = mutationScope("cancel", paymentId);
        String fingerprint = "cancel";
        if (idempotencyService.isReplay(scope, idempotencyKey, fingerprint)) {
            return getOrFail(paymentId);
        }

        PaymentIntentEntity payment = getOrFail(paymentId);
        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            recordMutation(scope, idempotencyKey, fingerprint, payment);
            return payment;
        }

        if (payment.getStatus() == PaymentStatus.CREATED || payment.getStatus() == PaymentStatus.RISK_SCORING) {
            payment.setStatus(PaymentStatus.CANCELLED);
            PaymentIntentEntity saved = paymentRepository.save(payment);
            recordMutation(scope, idempotencyKey, fingerprint, saved);
            auditService.append(
                    "payment.cancelled",
                    saved.getId(),
                    saved.getPayerAccountId(),
                    null,
                    correlationId,
                    Map.of("status", saved.getStatus().name())
            );
            publishPaymentEvent("payment.cancelled", saved, correlationId, idempotencyKey, Map.of());
            return saved;
        }

        if (payment.getStatus() != PaymentStatus.RESERVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Cancel only allowed before capture");
        }

        JournalResponse reversalJournal = releaseReservedFunds(payment, referenceId(payment.getId(), "cancel"), correlationId);

        payment.setStatus(PaymentStatus.CANCELLED);
        PaymentIntentEntity saved = paymentRepository.save(payment);
        recordMutation(scope, idempotencyKey, fingerprint, saved);

        auditService.append(
                "payment.cancelled",
                saved.getId(),
                saved.getPayerAccountId(),
                reversalJournal.id(),
                correlationId,
                Map.of("status", saved.getStatus().name())
        );
        publishPaymentEvent(
                "payment.cancelled",
                saved,
                correlationId,
                idempotencyKey,
                Map.of("journalId", reversalJournal.id())
        );
        return saved;
    }

    private JournalResponse reserveFunds(PaymentIntentEntity payment, String referenceId, String correlationId) {
        AccountEntity holdingAccount = accountService.getSystemHoldingAccount(payment.getCurrency());
        return ledgerService.createJournal(new CreateJournalRequest(
                JournalType.RESERVE,
                referenceId,
                List.of(
                        new CreateLedgerLegRequest(payment.getPayerAccountId(), com.ledgerforge.payments.ledger.LedgerDirection.DEBIT, payment.getAmount(), payment.getCurrency()),
                        new CreateLedgerLegRequest(holdingAccount.getId(), com.ledgerforge.payments.ledger.LedgerDirection.CREDIT, payment.getAmount(), payment.getCurrency())
                )
        ), correlationId);
    }

    private JournalResponse releaseReservedFunds(PaymentIntentEntity payment, String referenceId, String correlationId) {
        AccountEntity holdingAccount = accountService.getSystemHoldingAccount(payment.getCurrency());
        return ledgerService.createJournal(new CreateJournalRequest(
                JournalType.REVERSAL,
                referenceId,
                List.of(
                        new CreateLedgerLegRequest(holdingAccount.getId(), com.ledgerforge.payments.ledger.LedgerDirection.DEBIT, payment.getAmount(), payment.getCurrency()),
                        new CreateLedgerLegRequest(payment.getPayerAccountId(), com.ledgerforge.payments.ledger.LedgerDirection.CREDIT, payment.getAmount(), payment.getCurrency())
                )
        ), correlationId);
    }

    private JournalResponse postCapturedFundsAdjustment(PaymentIntentEntity payment,
                                                        JournalType journalType,
                                                        String referenceId,
                                                        BigDecimal fee,
                                                        String correlationId) {
        AccountEntity revenueAccount = accountService.getSystemRevenueAccount(payment.getCurrency());
        BigDecimal net = payment.getAmount().subtract(fee);
        return ledgerService.createJournal(new CreateJournalRequest(
                journalType,
                referenceId,
                List.of(
                        new CreateLedgerLegRequest(payment.getPayeeAccountId(), com.ledgerforge.payments.ledger.LedgerDirection.DEBIT, net, payment.getCurrency()),
                        new CreateLedgerLegRequest(revenueAccount.getId(), com.ledgerforge.payments.ledger.LedgerDirection.DEBIT, fee, payment.getCurrency()),
                        new CreateLedgerLegRequest(payment.getPayerAccountId(), com.ledgerforge.payments.ledger.LedgerDirection.CREDIT, payment.getAmount(), payment.getCurrency())
                )
        ), correlationId);
    }

    private void appendAdjustment(PaymentIntentEntity payment,
                                  PaymentAdjustmentType type,
                                  BigDecimal amount,
                                  BigDecimal feeAmount,
                                  String reason,
                                  JournalResponse journal,
                                  String correlationId,
                                  String idempotencyKey) {
        PaymentAdjustmentEntity adjustment = new PaymentAdjustmentEntity();
        adjustment.setPaymentId(payment.getId());
        adjustment.setType(type);
        adjustment.setAmount(amount);
        adjustment.setFeeAmount(feeAmount);
        adjustment.setCurrency(payment.getCurrency());
        adjustment.setReason(nullToBlank(reason));
        adjustment.setJournalId(journal.id());
        adjustment.setCorrelationId(correlationId);
        adjustment.setIdempotencyKey(idempotencyKey);
        paymentAdjustmentRepository.save(adjustment);
    }

    private void validatePaymentAccounts(UUID payerId, UUID payeeId, String currency) {
        if (payerId.equals(payeeId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Payer and payee accounts must be different");
        }

        AccountEntity payer = accountService.getOrFail(payerId);
        AccountEntity payee = accountService.getOrFail(payeeId);

        accountService.requirePaymentParticipationAllowed(payer, "payer");
        accountService.requirePaymentParticipationAllowed(payee, "payee");
        accountService.requireCurrencySupport(payer, currency);
        accountService.requireCurrencySupport(payee, currency);
    }

    private PaymentIntentEntity getOrFail(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));
    }

    private void ensureStatus(PaymentIntentEntity payment, PaymentStatus expected, String action) {
        if (payment.getStatus() != expected) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot " + action + " payment in status " + payment.getStatus().name());
        }
    }

    private BigDecimal calculateFee(BigDecimal amount) {
        return amount.multiply(FEE_RATE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveAmount(BigDecimal amount, Long amountCents) {
        BigDecimal resolved = resolveOptionalAmount(amount, amountCents);
        if (resolved == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Either amount or amountCents is required");
        }
        if (resolved.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
        return resolved;
    }

    private BigDecimal resolveOptionalAmount(BigDecimal amount, Long amountCents) {
        if (amount != null && amountCents != null) {
            BigDecimal centsValue = BigDecimal.valueOf(amountCents, 2);
            if (amount.compareTo(centsValue) != 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "amount and amountCents mismatch");
            }
        }

        BigDecimal resolved = amount;
        if (resolved == null && amountCents != null) {
            resolved = BigDecimal.valueOf(amountCents, 2);
        }
        if (resolved != null) {
            return resolved.setScale(4, RoundingMode.HALF_UP);
        }
        return null;
    }

    private BigDecimal requireFullAmountOrDefault(BigDecimal requestedAmount, BigDecimal fullAmount, String action) {
        if (requestedAmount == null) {
            return fullAmount;
        }
        if (requestedAmount.compareTo(fullAmount) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Partial " + action + "s are not supported in this workflow");
        }
        return requestedAmount;
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Currency is required");
        }
        return currency.toUpperCase();
    }

    private String mutationScope(String action, UUID paymentId) {
        return "payment." + action + "." + paymentId;
    }

    private String referencePrefix(UUID paymentId) {
        return "payment:" + paymentId + ":";
    }

    private String referenceId(UUID paymentId, String action) {
        return "payment:" + paymentId + ":" + action;
    }

    private String createFingerprint(PaymentIntentEntity payment) {
        return createFingerprint(
                payment.getPayerAccountId(),
                payment.getPayeeAccountId(),
                payment.getAmount(),
                payment.getCurrency()
        );
    }

    private String createFingerprint(UUID payerAccountId, UUID payeeAccountId, BigDecimal amount, String currency) {
        return payerAccountId + "|" + payeeAccountId + "|" + amount.toPlainString() + "|" + currency;
    }

    private String confirmFingerprint(ConfirmPaymentRequest request) {
        return "confirm|"
                + boolToken(request.newDevice()) + "|"
                + strToken(request.ipCountry()) + "|"
                + strToken(request.accountCountry()) + "|"
                + numToken(request.recentDeclines()) + "|"
                + numToken(request.accountAgeMinutes());
    }

    private void recordMutation(String scope,
                                String idempotencyKey,
                                String requestFingerprint,
                                PaymentIntentEntity payment) {
        String responsePayload = payment.getId() + ":" + payment.getStatus().name() + ":" + payment.getUpdatedAt();
        idempotencyService.record(scope, idempotencyKey, requestFingerprint, responsePayload);
    }

    private String boolToken(Boolean value) {
        return value == null ? "" : value.toString();
    }

    private String strToken(String value) {
        return value == null ? "" : value;
    }

    private String numToken(Number value) {
        return value == null ? "" : value.toString();
    }

    private String riskReasonSummary(List<FraudReason> reasons) {
        if (reasons.isEmpty()) {
            return "Risk score exceeds threshold";
        }
        return reasons.stream()
                .limit(3)
                .map(FraudReason::code)
                .reduce((a, b) -> a + "," + b)
                .orElse("Risk score exceeds threshold");
    }

    private String reviewFailureReason(List<FraudReason> reasons) {
        boolean timedOut = reasons.stream().anyMatch(reason -> "FRAUD_TIMEOUT".equals(reason.code()));
        if (timedOut) {
            return "Pending manual review after fraud timeout";
        }
        return "Pending manual review";
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private void publishPaymentEvent(String eventType,
                                     PaymentIntentEntity payment,
                                     String correlationId,
                                     String idempotencyKey,
                                     Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.getId());
        payload.put("status", payment.getStatus().name());
        payload.put("payerAccountId", payment.getPayerAccountId());
        payload.put("payeeAccountId", payment.getPayeeAccountId());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        if (payment.getRiskDecision() != null) {
            payload.put("riskDecision", payment.getRiskDecision().name());
        }
        if (payment.getRiskScore() != null) {
            payload.put("riskScore", payment.getRiskScore());
        }
        if (payment.getFailureReason() != null && !payment.getFailureReason().isBlank()) {
            payload.put("failureReason", payment.getFailureReason());
        }
        if (payment.getSettlementScheduledFor() != null) {
            payload.put("settlementScheduledFor", payment.getSettlementScheduledFor());
        }
        if (payment.getSettledAt() != null) {
            payload.put("settledAt", payment.getSettledAt());
        }
        if (payment.getSettlementBatchId() != null) {
            payload.put("settlementBatchId", payment.getSettlementBatchId());
        }
        payload.putAll(details);

        outboxService.enqueue(
                eventType,
                "payment",
                payment.getId(),
                payment.getId().toString(),
                correlationId,
                idempotencyKey,
                payload
        );
        notificationService.enqueuePaymentEvent(eventType, payment, correlationId, payload);
    }

    private Instant nextSettlementCutoff(Instant capturedAt) {
        ZonedDateTime capturedUtc = capturedAt.atZone(ZoneOffset.UTC);
        ZonedDateTime cutoff = capturedUtc.toLocalDate()
                .atTime(17, 0)
                .atZone(ZoneOffset.UTC);
        if (capturedUtc.isAfter(cutoff)) {
            cutoff = cutoff.plusDays(1);
        }
        return cutoff.toInstant();
    }

    private static class ExistingPaymentException extends RuntimeException {
        private final PaymentIntentEntity payment;

        private ExistingPaymentException(PaymentIntentEntity payment) {
            this.payment = payment;
        }
    }

    public PaymentIntentEntity createWithIdempotency(CreatePaymentRequest request, String idempotencyKey, String correlationId) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                return create(request, idempotencyKey, correlationId);
            }
            return requiresNewTransactionTemplate.execute(status -> create(request, idempotencyKey, correlationId));
        } catch (ExistingPaymentException existing) {
            return existing.payment;
        } catch (DataIntegrityViolationException ex) {
            return findCreatedPaymentByIdempotency(request, idempotencyKey);
        }
    }

    private PaymentIntentEntity findCreatedPaymentByIdempotency(CreatePaymentRequest request, String idempotencyKey) {
        BigDecimal amount = resolveAmount(request.amount(), request.amountCents());
        String currency = normalizeCurrency(request.currency());
        String requestedFingerprint = createFingerprint(
                request.payerAccountId(),
                request.payeeAccountId(),
                amount,
                currency
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            PaymentIntentEntity existing = requiresNewTransactionTemplate.execute(status ->
                    paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null)
            );
            if (existing == null) {
                LockSupport.parkNanos(25_000_000L);
                continue;
            }

            String existingFingerprint = createFingerprint(existing);
            if (!existingFingerprint.equals(requestedFingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "Idempotency key reused with different create payload");
            }
            return existing;
        }

        throw new ApiException(HttpStatus.CONFLICT, "Duplicate payment idempotency key");
    }
}
