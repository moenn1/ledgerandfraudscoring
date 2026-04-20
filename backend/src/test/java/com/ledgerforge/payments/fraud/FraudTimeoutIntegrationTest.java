package com.ledgerforge.payments.fraud;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.outbox.OutboxEventRepository;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentService;
import com.ledgerforge.payments.payment.PaymentStatus;
import com.ledgerforge.payments.payment.RiskDecision;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "ledgerforge.outbox.relay-enabled=false")
class FraudTimeoutIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @Autowired
    private FraudSignalRepository fraudSignalRepository;

    @SpyBean
    private FraudScoringService fraudScoringService;

    @AfterEach
    void resetSpy() {
        reset(fraudScoringService);
    }

    @Test
    void confirmFallsBackToManualReviewWhenFraudScoringTimesOut() {
        UUID payerId = createAccount("timeout-payer-" + UUID.randomUUID(), "USD");
        UUID payeeId = createAccount("timeout-payee-" + UUID.randomUUID(), "USD");
        String createKey = "timeout-create-" + UUID.randomUUID();
        String correlationId = "corr-timeout-" + UUID.randomUUID();

        PaymentIntentEntity payment = paymentService.createWithIdempotency(
                new CreatePaymentRequest(payerId, payeeId, null, 25_000L, "USD", createKey),
                createKey,
                correlationId
        );

        doThrow(new IllegalStateException("fraud scorer timed out", new TimeoutException("fraud scorer timed out")))
                .when(fraudScoringService)
                .evaluatePolicy(argThat(candidate -> candidate != null && payment.getId().equals(candidate.getId())), any(), any());

        PaymentIntentEntity confirmed = paymentService.confirm(
                payment.getId(),
                new ConfirmPaymentRequest(true, "US", "US", 0, 10),
                "timeout-confirm-" + UUID.randomUUID(),
                correlationId
        );

        ReviewCaseEntity reviewCase = reviewCaseRepository.findByPaymentId(payment.getId()).orElseThrow();
        FraudSignalEntity timeoutSignal = fraudSignalRepository
                .findByPaymentIdOrderByWeightDescCreatedAtAsc(payment.getId())
                .stream()
                .filter(signal -> "FRAUD_TIMEOUT".equals(signal.getSignalType()))
                .findFirst()
                .orElseThrow();

        assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.RISK_SCORING);
        assertThat(confirmed.getRiskDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(confirmed.getRiskScore()).isEqualTo(40);
        assertThat(confirmed.getFailureReason()).isEqualTo("Pending manual review after fraud timeout");
        assertThat(reviewCase.getReason()).contains("FRAUD_TIMEOUT");
        assertThat(timeoutSignal.getSignalValue()).contains("timed out");
        assertThat(outboxEventRepository.findAllByOrderByCreatedAtAsc()).filteredOn(event ->
                "payment.review_required".equals(event.getEventType())
                        && payment.getId().equals(event.getAggregateId())
        ).hasSize(1);
        assertThat(auditEventRepository.findAll()).filteredOn(event ->
                "payment.review_required".equals(event.getEventType())
                        && payment.getId().equals(event.getPaymentId())
        ).hasSize(1);
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
