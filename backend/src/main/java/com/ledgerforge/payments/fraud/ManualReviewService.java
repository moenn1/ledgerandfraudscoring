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
import com.ledgerforge.payments.outbox.OutboxService;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentIntentRepository;
import com.ledgerforge.payments.payment.PaymentStatus;
import com.ledgerforge.payments.payment.RiskDecision;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
        outboxService.enqueue(
                "fraud.review_case.opened",
                "review_case",
                saved.getId(),
                payment.getId().toString(),
                correlationId,
                null,
                Map.of(
                        "reviewCaseId", saved.getId(),
                        "paymentId", payment.getId(),
                        "status", saved.getStatus().name(),
                        "reason", saved.getReason(),
                        "assignedTo", saved.getAssignedTo()
                )
        );
        return saved;
    }

    @Transactional
    public ReviewCaseEntity decide(UUID reviewCaseId, ReviewDecisionRequest request, String correlationId, String actorId) {
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
            accountService.requirePaymentParticipationAllowed(accountService.getOrFail(payment.getPayerAccountId()), "payer");
            accountService.requirePaymentParticipationAllowed(accountService.getOrFail(payment.getPayeeAccountId()), "payee");
            AccountEntity holdingAccount = accountService.getSystemHoldingAccount(payment.getCurrency());
            reserveJournal = ledgerService.postJournal(
                    JournalType.RESERVE,
                    "payment:" + payment.getId() + ":reserve",
                    List.of(
                            new LedgerLeg(payment.getPayerAccountId(), LedgerDirection.DEBIT, payment.getAmount(), payment.getCurrency()),
                            new LedgerLeg(holdingAccount.getId(), LedgerDirection.CREDIT, payment.getAmount(), payment.getCurrency())
                    ),
                    correlationId
            );
            payment.setStatus(PaymentStatus.RESERVED);
        } else {
            reviewCase.setStatus(ReviewCaseStatus.REJECTED);
            payment.setStatus(PaymentStatus.REJECTED);
            payment.setFailureReason("Rejected during manual review");
        }

        reviewCaseRepository.save(reviewCase);
        paymentIntentRepository.save(payment);

        Map<String, Object> details = new HashMap<>();
        details.put("reviewCaseId", reviewCase.getId());
        details.put("decision", request.decision().name());
        details.put("actor", actorId);
        details.put("note", request.note() == null ? "" : request.note());
        if (reserveJournal != null) {
            details.put("journalId", reserveJournal.getId());
        }
        auditService.appendWithActor(
                "fraud.review_case.decided",
                payment.getId(),
                payment.getPayerAccountId(),
                reserveJournal == null ? null : reserveJournal.getId(),
                correlationId,
                "operator",
                actorId,
                details
        );
        outboxService.enqueue(
                "fraud.review_case.decided",
                "review_case",
                reviewCase.getId(),
                payment.getId().toString(),
                correlationId,
                null,
                Map.of(
                        "reviewCaseId", reviewCase.getId(),
                        "paymentId", payment.getId(),
                        "decision", request.decision().name(),
                        "actor", actorId,
                        "note", request.note() == null ? "" : request.note(),
                        "status", reviewCase.getStatus().name(),
                        "journalId", reserveJournal == null ? "" : reserveJournal.getId()
                )
        );
        if (payment.getStatus() == PaymentStatus.RESERVED) {
            publishPaymentReserved(payment, correlationId, reserveJournal);
        }

        return reviewCase;
    }

    private void publishPaymentReserved(PaymentIntentEntity payment,
                                        String correlationId,
                                        JournalTransactionEntity reserveJournal) {
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
        if (reserveJournal != null) {
            payload.put("journalId", reserveJournal.getId());
        }
        outboxService.enqueue(
                "payment.reserved",
                "payment",
                payment.getId(),
                payment.getId().toString(),
                correlationId,
                null,
                payload
        );
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
}
