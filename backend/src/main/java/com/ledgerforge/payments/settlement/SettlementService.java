package com.ledgerforge.payments.settlement;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.ledger.CreateJournalRequest;
import com.ledgerforge.payments.ledger.CreateLedgerLegRequest;
import com.ledgerforge.payments.ledger.JournalResponse;
import com.ledgerforge.payments.ledger.JournalType;
import com.ledgerforge.payments.ledger.LedgerDirection;
import com.ledgerforge.payments.ledger.LedgerService;
import com.ledgerforge.payments.notification.NotificationService;
import com.ledgerforge.payments.outbox.OutboxService;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentIntentRepository;
import com.ledgerforge.payments.payment.PaymentStatus;
import com.ledgerforge.payments.settlement.api.PayoutResponse;
import com.ledgerforge.payments.settlement.api.PayoutRunResponse;
import com.ledgerforge.payments.settlement.api.RunPayoutRequest;
import com.ledgerforge.payments.settlement.api.RunSettlementRequest;
import com.ledgerforge.payments.settlement.api.SettlementBatchResponse;
import com.ledgerforge.payments.settlement.api.SettlementRunResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SettlementService {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.03");
    private static final int DEFAULT_PAYOUT_DELAY_MINUTES = 120;

    private final PaymentIntentRepository paymentIntentRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final PayoutRepository payoutRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final OutboxService outboxService;
    private final Clock clock;

    public SettlementService(PaymentIntentRepository paymentIntentRepository,
                             SettlementBatchRepository settlementBatchRepository,
                             PayoutRepository payoutRepository,
                             AccountService accountService,
                             LedgerService ledgerService,
                             AuditService auditService,
                             NotificationService notificationService,
                             OutboxService outboxService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.payoutRepository = payoutRepository;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.outboxService = outboxService;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchEntity> listBatches() {
        return settlementBatchRepository.findAllByOrderByCutoffAtDescCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<PayoutEntity> listPayouts() {
        return payoutRepository.findAllByOrderByScheduledForDescCreatedAtDesc();
    }

    @Transactional
    public SettlementRunResponse runSettlements(RunSettlementRequest request, String correlationId) {
        RunSettlementRequest normalizedRequest = request == null
                ? new RunSettlementRequest(null, null)
                : request;
        Instant asOf = normalizedRequest.asOf() == null ? Instant.now(clock) : normalizedRequest.asOf();
        int payoutDelayMinutes = normalizedRequest.payoutDelayMinutes() == null
                ? DEFAULT_PAYOUT_DELAY_MINUTES
                : normalizedRequest.payoutDelayMinutes();
        if (payoutDelayMinutes < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "payoutDelayMinutes must be greater than or equal to zero");
        }

        List<PaymentIntentEntity> duePayments = paymentIntentRepository
                .findByStatusAndSettlementScheduledForLessThanEqualOrderBySettlementScheduledForAscCreatedAtAsc(
                        PaymentStatus.CAPTURED,
                        asOf
                );

        Map<BatchKey, List<PaymentIntentEntity>> groupedPayments = new LinkedHashMap<>();
        for (PaymentIntentEntity payment : duePayments) {
            if (payment.getSettlementScheduledFor() == null) {
                continue;
            }
            groupedPayments.computeIfAbsent(
                    new BatchKey(payment.getSettlementScheduledFor(), payment.getCurrency()),
                    ignored -> new ArrayList<>()
            ).add(payment);
        }

        List<SettlementBatchEntity> touchedBatches = new ArrayList<>();
        int createdOrUpdatedPayouts = 0;
        for (Map.Entry<BatchKey, List<PaymentIntentEntity>> entry : groupedPayments.entrySet()) {
            SettlementBatchEntity batch = upsertBatch(entry.getKey(), entry.getValue(), asOf);
            touchedBatches.add(batch);

            Map<UUID, PayoutEntity> payoutsByPayee = upsertPayouts(batch, entry.getValue(), payoutDelayMinutes);
            createdOrUpdatedPayouts += payoutsByPayee.size();

            for (PaymentIntentEntity payment : entry.getValue()) {
                payment.setStatus(PaymentStatus.SETTLED);
                payment.setSettledAt(asOf);
                payment.setSettlementBatchId(batch.getId());
                PayoutEntity payout = payoutsByPayee.get(payment.getPayeeAccountId());
                auditService.append(
                        "payment.settled",
                        payment.getId(),
                        payment.getPayeeAccountId(),
                        null,
                        correlationId,
                        Map.of(
                                "status", payment.getStatus().name(),
                                "settlementBatchId", batch.getId(),
                                "settlementCutoffAt", batch.getCutoffAt(),
                                "payoutId", payout.getId(),
                                "payoutScheduledFor", payout.getScheduledFor()
                        )
                );
                publishPaymentSettled(payment, correlationId, batch, payout);
            }
            paymentIntentRepository.saveAll(entry.getValue());

            auditService.append(
                "settlement.batch.completed",
                null,
                null,
                null,
                correlationId,
                Map.of(
                        "settlementBatchId", batch.getId(),
                        "currency", batch.getCurrency(),
                        "paymentCount", batch.getPaymentCount(),
                        "netAmount", batch.getNetAmount()
                )
            );
            publishSettlementBatchCompleted(batch, correlationId, payoutsByPayee.values());
            payoutsByPayee.values().forEach(payout -> publishPayoutScheduled(payout, correlationId));
        }

        return new SettlementRunResponse(
                asOf,
                duePayments.size(),
                touchedBatches.size(),
                createdOrUpdatedPayouts,
                touchedBatches.stream().map(SettlementBatchResponse::from).toList()
        );
    }

    @Transactional
    public PayoutRunResponse runPayouts(RunPayoutRequest request, String correlationId) {
        RunPayoutRequest normalizedRequest = request == null
                ? new RunPayoutRequest(null)
                : request;
        Instant asOf = normalizedRequest.asOf() == null ? Instant.now(clock) : normalizedRequest.asOf();

        List<PayoutEntity> duePayouts = payoutRepository.findByStatusInAndScheduledForLessThanEqualOrderByScheduledForAscCreatedAtAsc(
                List.of(PayoutStatus.SCHEDULED, PayoutStatus.DELAYED),
                asOf
        );

        int paidCount = 0;
        int delayedCount = 0;
        List<PayoutEntity> touchedPayouts = new ArrayList<>();
        for (PayoutEntity payout : duePayouts) {
            BigDecimal availableBalance = accountService.balance(payout.getPayeeAccountId(), payout.getCurrency()).balance();
            if (availableBalance.compareTo(payout.getNetAmount()) < 0) {
                payout.setStatus(PayoutStatus.DELAYED);
                payout.setDelayReason("Insufficient available balance for payout execution");
                payout.setJournalId(null);
                payout.setExecutedAt(null);
                payoutRepository.save(payout);
                delayedCount++;
                touchedPayouts.add(payout);

                auditService.append(
                        "payout.delayed",
                        null,
                        payout.getPayeeAccountId(),
                        null,
                        correlationId,
                        Map.of(
                                "payoutId", payout.getId(),
                                "settlementBatchId", payout.getSettlementBatchId(),
                                "availableBalance", availableBalance,
                                "requiredNetAmount", payout.getNetAmount(),
                                "currency", payout.getCurrency()
                        )
                );
                publishPayoutDelayed(payout, availableBalance, correlationId);
                continue;
            }

            JournalResponse payoutJournal = postPayoutJournal(payout, correlationId);
            payout.setStatus(PayoutStatus.PAID);
            payout.setDelayReason(null);
            payout.setExecutedAt(asOf);
            payout.setJournalId(payoutJournal.id());
            payoutRepository.save(payout);
            paidCount++;
            touchedPayouts.add(payout);

            auditService.append(
                    "payout.paid",
                    null,
                    payout.getPayeeAccountId(),
                    payoutJournal.id(),
                    correlationId,
                    Map.of(
                            "payoutId", payout.getId(),
                            "settlementBatchId", payout.getSettlementBatchId(),
                            "netAmount", payout.getNetAmount(),
                            "currency", payout.getCurrency()
                    )
            );
            publishPayoutPaid(payout, payoutJournal, correlationId);
        }

        return new PayoutRunResponse(
                asOf,
                paidCount,
                delayedCount,
                touchedPayouts.stream().map(PayoutResponse::from).toList()
        );
    }

    private SettlementBatchEntity upsertBatch(BatchKey batchKey,
                                              List<PaymentIntentEntity> payments,
                                              Instant asOf) {
        SettlementBatchEntity batch = settlementBatchRepository
                .findFirstByCutoffAtAndCurrency(batchKey.cutoffAt(), batchKey.currency())
                .orElseGet(SettlementBatchEntity::new);
        batch.setCutoffAt(batchKey.cutoffAt());
        batch.setCurrency(batchKey.currency());
        batch.setStatus(SettlementBatchStatus.COMPLETED);
        batch.setPaymentCount(payments.size());
        batch.setGrossAmount(sumGross(payments));
        batch.setFeeAmount(sumFees(payments));
        batch.setNetAmount(batch.getGrossAmount().subtract(batch.getFeeAmount()));
        batch.setCompletedAt(asOf);
        return settlementBatchRepository.save(batch);
    }

    private Map<UUID, PayoutEntity> upsertPayouts(SettlementBatchEntity batch,
                                                  List<PaymentIntentEntity> payments,
                                                  int payoutDelayMinutes) {
        Map<UUID, List<PaymentIntentEntity>> paymentsByPayee = new LinkedHashMap<>();
        for (PaymentIntentEntity payment : payments) {
            paymentsByPayee.computeIfAbsent(payment.getPayeeAccountId(), ignored -> new ArrayList<>()).add(payment);
        }

        Map<UUID, PayoutEntity> payoutsByPayee = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<PaymentIntentEntity>> entry : paymentsByPayee.entrySet()) {
            PayoutEntity payout = payoutRepository
                    .findFirstBySettlementBatchIdAndPayeeAccountId(batch.getId(), entry.getKey())
                    .orElseGet(PayoutEntity::new);
            boolean alreadyPaid = payout.getStatus() == PayoutStatus.PAID;
            payout.setSettlementBatchId(batch.getId());
            payout.setPayeeAccountId(entry.getKey());
            payout.setCurrency(batch.getCurrency());
            payout.setGrossAmount(sumGross(entry.getValue()));
            payout.setFeeAmount(sumFees(entry.getValue()));
            payout.setNetAmount(payout.getGrossAmount().subtract(payout.getFeeAmount()));
            if (!alreadyPaid) {
                payout.setStatus(PayoutStatus.SCHEDULED);
                payout.setScheduledFor(batch.getCutoffAt().plusSeconds((long) payoutDelayMinutes * 60));
                payout.setDelayReason(null);
                payout.setJournalId(null);
                payout.setExecutedAt(null);
            }
            payoutsByPayee.put(entry.getKey(), payoutRepository.save(payout));
        }
        return payoutsByPayee;
    }

    private JournalResponse postPayoutJournal(PayoutEntity payout, String correlationId) {
        AccountEntity payoutClearingAccount = accountService.getSystemPayoutClearingAccount(payout.getCurrency());
        return ledgerService.createJournal(new CreateJournalRequest(
                JournalType.PAYOUT,
                payoutReferenceId(payout.getId()),
                List.of(
                        new CreateLedgerLegRequest(payout.getPayeeAccountId(), LedgerDirection.DEBIT, payout.getNetAmount(), payout.getCurrency()),
                        new CreateLedgerLegRequest(payoutClearingAccount.getId(), LedgerDirection.CREDIT, payout.getNetAmount(), payout.getCurrency())
                )
        ), correlationId);
    }

    private void publishPaymentSettled(PaymentIntentEntity payment,
                                       String correlationId,
                                       SettlementBatchEntity batch,
                                       PayoutEntity payout) {
        outboxService.enqueue(
                "payment.settled",
                "payment",
                payment.getId(),
                payment.getId().toString(),
                correlationId,
                null,
                Map.of(
                        "paymentId", payment.getId(),
                        "status", payment.getStatus().name(),
                        "payerAccountId", payment.getPayerAccountId(),
                        "payeeAccountId", payment.getPayeeAccountId(),
                        "amount", payment.getAmount(),
                        "currency", payment.getCurrency(),
                        "settledAt", payment.getSettledAt(),
                        "settlementBatchId", batch.getId(),
                        "payoutId", payout.getId(),
                        "payoutScheduledFor", payout.getScheduledFor()
                )
        );
        notificationService.enqueuePaymentEvent(
                "payment.settled",
                payment,
                correlationId,
                Map.of(
                        "paymentId", payment.getId(),
                        "status", payment.getStatus().name(),
                        "payerAccountId", payment.getPayerAccountId(),
                        "payeeAccountId", payment.getPayeeAccountId(),
                        "amount", payment.getAmount(),
                        "currency", payment.getCurrency(),
                        "settledAt", payment.getSettledAt(),
                        "settlementBatchId", batch.getId(),
                        "payoutId", payout.getId(),
                        "payoutScheduledFor", payout.getScheduledFor()
                )
        );
    }

    private void publishSettlementBatchCompleted(SettlementBatchEntity batch,
                                                 String correlationId,
                                                 Collection<PayoutEntity> payouts) {
        outboxService.enqueue(
                "settlement.batch.completed",
                "settlement_batch",
                batch.getId(),
                batch.getCurrency(),
                correlationId,
                null,
                Map.of(
                        "settlementBatchId", batch.getId(),
                        "currency", batch.getCurrency(),
                        "status", batch.getStatus().name(),
                        "paymentCount", batch.getPaymentCount(),
                        "grossAmount", batch.getGrossAmount(),
                        "feeAmount", batch.getFeeAmount(),
                        "netAmount", batch.getNetAmount(),
                        "cutoffAt", batch.getCutoffAt(),
                        "completedAt", batch.getCompletedAt(),
                        "payoutIds", payouts.stream().map(PayoutEntity::getId).toList()
                )
        );
    }

    private void publishPayoutScheduled(PayoutEntity payout, String correlationId) {
        outboxService.enqueue(
                "payout.scheduled",
                "payout",
                payout.getId(),
                payout.getPayeeAccountId().toString(),
                correlationId,
                null,
                payoutPayload(payout)
        );
    }

    private void publishPayoutPaid(PayoutEntity payout, JournalResponse payoutJournal, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>(payoutPayload(payout));
        payload.put("journalId", payoutJournal.id());
        outboxService.enqueue(
                "payout.paid",
                "payout",
                payout.getId(),
                payout.getPayeeAccountId().toString(),
                correlationId,
                null,
                payload
        );
    }

    private void publishPayoutDelayed(PayoutEntity payout, BigDecimal availableBalance, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>(payoutPayload(payout));
        payload.put("availableBalance", availableBalance);
        outboxService.enqueue(
                "payout.delayed",
                "payout",
                payout.getId(),
                payout.getPayeeAccountId().toString(),
                correlationId,
                null,
                payload
        );
    }

    private Map<String, Object> payoutPayload(PayoutEntity payout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payoutId", payout.getId());
        payload.put("settlementBatchId", payout.getSettlementBatchId());
        payload.put("payeeAccountId", payout.getPayeeAccountId());
        payload.put("currency", payout.getCurrency());
        payload.put("status", payout.getStatus().name());
        payload.put("grossAmount", payout.getGrossAmount());
        payload.put("feeAmount", payout.getFeeAmount());
        payload.put("netAmount", payout.getNetAmount());
        payload.put("scheduledFor", payout.getScheduledFor());
        payload.put("delayReason", payout.getDelayReason() == null ? "" : payout.getDelayReason());
        payload.put("executedAt", payout.getExecutedAt());
        if (payout.getJournalId() != null) {
            payload.put("journalId", payout.getJournalId());
        }
        return payload;
    }

    private BigDecimal sumGross(Collection<PaymentIntentEntity> payments) {
        return payments.stream()
                .map(PaymentIntentEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumFees(Collection<PaymentIntentEntity> payments) {
        return payments.stream()
                .map(payment -> payment.getAmount().multiply(FEE_RATE).setScale(4, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String payoutReferenceId(UUID payoutId) {
        return "payout:" + payoutId + ":execute";
    }

    private record BatchKey(Instant cutoffAt, String currency) {
    }
}
