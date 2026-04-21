package com.ledgerforge.payments.fraud;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.fraud.api.ReviewDecisionRequest;
import com.ledgerforge.payments.ledger.JournalTransactionEntity;
import com.ledgerforge.payments.ledger.JournalType;
import com.ledgerforge.payments.ledger.LedgerDirection;
import com.ledgerforge.payments.ledger.LedgerLeg;
import com.ledgerforge.payments.ledger.LedgerService;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentIntentRepository;
import com.ledgerforge.payments.payment.PaymentStatus;
import com.ledgerforge.payments.payment.RiskDecision;
import com.ledgerforge.payments.outbox.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ManualReviewService {

    private static final String DEFAULT_ASSIGNEE = "risk.reviewer@ledgerforge.local";

    private final ReviewCaseRepository reviewCaseRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public ManualReviewService(ReviewCaseRepository reviewCaseRepository,
                               PaymentIntentRepository paymentIntentRepository,
                               AccountService accountService,
                               LedgerService ledgerService,
                               AuditService auditService,
                               OutboxService outboxService) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    @Transactional(readOnly = true)
    public List<ReviewCaseEntity> listQueue() {
        return reviewCaseRepository.findQueue();
    }

    @Transactional
    public ReviewCaseEntity openCaseIfAbsent(PaymentIntentEntity payment,
                                             List<FraudReason> reasons,
                                             String correlationId) {
        ReviewCaseEntity existing = reviewCaseRepository.findByPaymentId(payment.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }

        ReviewCaseEntity reviewCase = new ReviewCaseEntity();
        reviewCase.setPaymentId(payment.getId());
        reviewCase.setReason(reasonSummary(reasons));
        reviewCase.setStatus(ReviewCaseStatus.OPEN);
        reviewCase.setAssignedTo(DEFAULT_ASSIGNEE);

        ReviewCaseEntity saved = reviewCaseRepository.save(reviewCase);
        auditService.append(
                "fraud.review_case.opened",
                payment.getId(),
                payment.getPayerAccountId(),
                null,
                correlationId,
                Map.of(
                        "reviewCaseId", saved.getId(),
                        "reason", saved.getReason(),
                        "assignedTo", saved.getAssignedTo()
                )
        );
        return saved;
    }

    @Transactional
    public ReviewCaseEntity decide(UUID reviewCaseId, ReviewDecisionRequest request, String correlationId) {
        ReviewCaseEntity reviewCase = reviewCaseRepository.findById(reviewCaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Review case not found: " + reviewCaseId));
        if (reviewCase.getStatus() != ReviewCaseStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "Review case already decided: " + reviewCaseId);
        }

        PaymentIntentEntity payment = paymentIntentRepository.findById(reviewCase.getPaymentId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found for review case: " + reviewCaseId));

        if (payment.getRiskDecision() != RiskDecision.REVIEW || payment.getStatus() != PaymentStatus.RISK_SCORING) {
            throw new ApiException(HttpStatus.CONFLICT, "Late review decision cannot be applied to current payment state");
        }

        JournalTransactionEntity reserveJournal = null;
        if (request.decision() == ReviewDecisionRequest.ReviewDecision.APPROVE) {
            reviewCase.setStatus(ReviewCaseStatus.APPROVED);
            payment.setStatus(PaymentStatus.APPROVED);
            payment.setRiskDecision(RiskDecision.APPROVE);
            payment.setFailureReason(null);
            AccountEntity holdingAccount = accountService.getSystemHoldingAccount(payment.getCurrency());
            reserveJournal = ledgerService.postJournal(
                    JournalType.RESERVE,
                    "payment:" + payment.getId() + ":reserve",
                    List.of(
                            new LedgerLeg(payment.getPayerAccountId(), LedgerDirection.DEBIT, payment.getAmount(), payment.getCurrency()),
                            new LedgerLeg(holdingAccount.getId(), LedgerDirection.CREDIT, payment.getAmount(), payment.getCurrency())
                    )
            );
            payment.setStatus(PaymentStatus.RESERVED);
        } else {
            reviewCase.setStatus(ReviewCaseStatus.REJECTED);
            payment.setStatus(PaymentStatus.REJECTED);
            payment.setFailureReason("Rejected during manual review");
        }

        ReviewCaseEntity savedReviewCase = reviewCaseRepository.save(reviewCase);
        PaymentIntentEntity savedPayment = paymentIntentRepository.save(payment);

        Map<String, Object> details = new HashMap<>();
        details.put("reviewCaseId", savedReviewCase.getId());
        details.put("decision", request.decision().name());
        details.put("actor", request.actor());
        details.put("note", request.note() == null ? "" : request.note());
        if (reserveJournal != null) {
            details.put("journalId", reserveJournal.getId());
        }
        auditService.append(
                "fraud.review_case.decided",
                savedPayment.getId(),
                savedPayment.getPayerAccountId(),
                reserveJournal == null ? null : reserveJournal.getId(),
                correlationId,
                details
        );

        if (reserveJournal != null) {
            emitReservedPaymentMutationEvent(savedPayment, savedReviewCase, request, reserveJournal, correlationId);
        }

        return savedReviewCase;
    }

    private String reasonSummary(List<FraudReason> reasons) {
        if (reasons.isEmpty()) {
            return "Risk policy requires manual review";
        }
        return reasons.stream()
                .limit(3)
                .map(reason -> reason.code() + ": " + reason.message())
                .reduce((a, b) -> a + " | " + b)
                .orElse("Risk policy requires manual review");
    }

    private void emitReservedPaymentMutationEvent(PaymentIntentEntity payment,
                                                  ReviewCaseEntity reviewCase,
                                                  ReviewDecisionRequest request,
                                                  JournalTransactionEntity reserveJournal,
                                                  String correlationId) {
        Map<String, Object> eventDetails = new HashMap<>();
        eventDetails.put("status", payment.getStatus().name());
        eventDetails.put("riskScore", payment.getRiskScore());
        eventDetails.put("riskDecision", payment.getRiskDecision().name());
        eventDetails.put("reviewCaseId", reviewCase.getId());
        eventDetails.put("reviewDecision", request.decision().name());
        eventDetails.put("reviewActor", request.actor());
        eventDetails.put("reviewNote", request.note() == null ? "" : request.note());

        auditService.append(
                "payment.reserved",
                payment.getId(),
                payment.getPayerAccountId(),
                reserveJournal.getId(),
                correlationId,
                eventDetails
        );
        outboxService.enqueue(
                "payment.reserved",
                payment.getId(),
                reserveJournal.getId(),
                correlationId,
                eventDetails
        );
    }
}
