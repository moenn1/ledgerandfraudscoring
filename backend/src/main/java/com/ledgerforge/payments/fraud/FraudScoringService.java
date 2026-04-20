package com.ledgerforge.payments.fraud;

import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentIntentRepository;
import com.ledgerforge.payments.payment.PaymentStatus;
import com.ledgerforge.payments.payment.RiskDecision;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class FraudScoringService {

    private static final BigDecimal NEW_DEVICE_AMOUNT_THRESHOLD = new BigDecimal("500.00");
    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("1000.00");

    private final PaymentIntentRepository paymentIntentRepository;
    private final FraudSignalRepository fraudSignalRepository;

    public FraudScoringService(PaymentIntentRepository paymentIntentRepository,
                               FraudSignalRepository fraudSignalRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.fraudSignalRepository = fraudSignalRepository;
    }

    @Transactional
    public FraudEvaluation score(PaymentIntentEntity payment,
                                 ConfirmPaymentRequest request,
                                 Instant payerCreatedAt) {
        List<FraudReason> reasons = new ArrayList<>();
        Instant now = Instant.now();

        long velocityCount = paymentIntentRepository.countByPayerAccountIdAndCreatedAtAfter(
                payment.getPayerAccountId(),
                now.minusSeconds(60)
        );
        if (velocityCount > 3) {
            reasons.add(new FraudReason(
                    "VELOCITY_SPIKE",
                    "High payment velocity in the last minute (count=" + velocityCount + ")",
                    25
            ));
        }

        if (Boolean.TRUE.equals(request.newDevice())
                && payment.getAmount().compareTo(NEW_DEVICE_AMOUNT_THRESHOLD) >= 0) {
            reasons.add(new FraudReason(
                    "NEW_DEVICE_HIGH_AMOUNT",
                    "New device combined with high-value amount",
                    20
            ));
        }

        if (request.ipCountry() != null && request.accountCountry() != null
                && !request.ipCountry().equalsIgnoreCase(request.accountCountry())) {
            reasons.add(new FraudReason(
                    "GEO_MISMATCH",
                    "IP country differs from account profile country",
                    20
            ));
        }

        long recentDeclines = request.recentDeclines() != null
                ? request.recentDeclines()
                : paymentIntentRepository.countByPayerAccountIdAndStatusAndUpdatedAtAfter(
                        payment.getPayerAccountId(),
                        PaymentStatus.REJECTED,
                        now.minusSeconds(900)
                );
        if (recentDeclines >= 2) {
            reasons.add(new FraudReason(
                    "REPEATED_DECLINES",
                    "Multiple declines detected in a short window",
                    20
            ));
        }

        long priorCount = paymentIntentRepository.countByPayerAccountIdAndIdNot(payment.getPayerAccountId(), payment.getId());
        BigDecimal baseline = paymentIntentRepository.averageAmountForPayerExcludingPayment(payment.getPayerAccountId(), payment.getId());
        if (priorCount >= 3
                && baseline != null
                && baseline.compareTo(BigDecimal.ZERO) > 0
                && payment.getAmount().compareTo(baseline.multiply(BigDecimal.valueOf(3))) >= 0) {
            reasons.add(new FraudReason(
                    "AMOUNT_ANOMALY",
                    "Amount is anomalous versus historical baseline",
                    25
            ));
        }

        boolean newAccount = request.accountAgeMinutes() != null
                ? request.accountAgeMinutes() <= 60
                : payerCreatedAt != null && Duration.between(payerCreatedAt, now).toMinutes() <= 60;
        if (newAccount && payment.getAmount().compareTo(HIGH_VALUE_THRESHOLD) >= 0) {
            reasons.add(new FraudReason(
                    "NEW_ACCOUNT_HIGH_VALUE",
                    "New account attempted immediate high-value transfer",
                    20
            ));
        }

        int score = Math.min(100, reasons.stream().mapToInt(FraudReason::weight).sum());
        RiskDecision decision = toDecision(score);

        persistSignals(payment.getId(), reasons);
        return new FraudEvaluation(score, decision, reasons);
    }

    @Transactional(readOnly = true)
    public FraudEvaluation loadPersistedEvaluation(UUID paymentId, int score, RiskDecision decision) {
        List<FraudReason> reasons = fraudSignalRepository.findByPaymentIdOrderByWeightDescCreatedAtAsc(paymentId)
                .stream()
                .map(signal -> new FraudReason(signal.getSignalType(), signal.getSignalValue(), signal.getWeight()))
                .toList();
        return new FraudEvaluation(score, decision, reasons);
    }

    private RiskDecision toDecision(int score) {
        if (score >= 70) {
            return RiskDecision.REJECT;
        }
        if (score >= 40) {
            return RiskDecision.REVIEW;
        }
        return RiskDecision.APPROVE;
    }

    private void persistSignals(UUID paymentId, List<FraudReason> reasons) {
        for (FraudReason reason : reasons) {
            FraudSignalEntity signal = new FraudSignalEntity();
            signal.setPaymentId(paymentId);
            signal.setSignalType(reason.code().toUpperCase(Locale.ROOT));
            signal.setSignalValue(reason.message());
            signal.setWeight(reason.weight());
            fraudSignalRepository.save(signal);
        }
    }
}
