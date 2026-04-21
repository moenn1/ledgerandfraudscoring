package com.ledgerforge.payments.common.telemetry;

import com.ledgerforge.payments.ledger.LedgerVerificationResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LedgerVerificationMetrics {

    private static final String UNBALANCED_JOURNALS = "unbalanced_journals";
    private static final String MIXED_CURRENCY_JOURNALS = "mixed_currency_journals";
    private static final String ACCOUNT_CURRENCY_MISMATCHES = "account_currency_mismatches";
    private static final String DUPLICATE_PAYMENT_JOURNALS = "duplicate_payment_journals";
    private static final String MUTATION_EVENT_RECONCILIATION = "mutation_event_reconciliation_findings";
    private static final String PAYMENT_LIFECYCLE_MISMATCHES = "payment_lifecycle_mismatches";

    private final MeterRegistry meterRegistry;
    private final AtomicInteger lastIssueCount = new AtomicInteger();
    private final AtomicInteger lastHealthy = new AtomicInteger();
    private final AtomicLong lastRunEpochSeconds = new AtomicLong();
    private final Map<String, AtomicInteger> categoryCounts = new ConcurrentHashMap<>();

    public LedgerVerificationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder("ledgerforge.ledger.verification.last.issue_count", lastIssueCount, AtomicInteger::get)
                .description("Issue count from the most recent ledger verification run")
                .register(meterRegistry);
        Gauge.builder("ledgerforge.ledger.verification.last.healthy", lastHealthy, AtomicInteger::get)
                .description("Whether the most recent ledger verification run passed all checks (1 healthy, 0 unhealthy)")
                .register(meterRegistry);
        Gauge.builder("ledgerforge.ledger.verification.last.run.epoch.seconds", lastRunEpochSeconds, AtomicLong::get)
                .description("Unix epoch seconds for the most recent ledger verification run")
                .register(meterRegistry);

        registerCategoryGauge(UNBALANCED_JOURNALS);
        registerCategoryGauge(MIXED_CURRENCY_JOURNALS);
        registerCategoryGauge(ACCOUNT_CURRENCY_MISMATCHES);
        registerCategoryGauge(DUPLICATE_PAYMENT_JOURNALS);
        registerCategoryGauge(MUTATION_EVENT_RECONCILIATION);
        registerCategoryGauge(PAYMENT_LIFECYCLE_MISMATCHES);
    }

    public Timer.Sample startVerification() {
        return Timer.start(meterRegistry);
    }

    public void recordVerification(LedgerVerificationResponse verification, Timer.Sample sample) {
        lastIssueCount.set(verification.issueCount());
        lastHealthy.set(verification.allChecksPassed() ? 1 : 0);
        lastRunEpochSeconds.set(verification.generatedAt().getEpochSecond());

        categoryCounts.get(UNBALANCED_JOURNALS).set(verification.unbalancedJournals().size());
        categoryCounts.get(MIXED_CURRENCY_JOURNALS).set(verification.mixedCurrencyJournals().size());
        categoryCounts.get(ACCOUNT_CURRENCY_MISMATCHES).set(verification.accountCurrencyMismatches().size());
        categoryCounts.get(DUPLICATE_PAYMENT_JOURNALS).set(verification.duplicatePaymentJournals().size());
        categoryCounts.get(MUTATION_EVENT_RECONCILIATION).set(verification.mutationEventReconciliationFindings().size());
        categoryCounts.get(PAYMENT_LIFECYCLE_MISMATCHES).set(verification.paymentLifecycleMismatches().size());

        sample.stop(Timer.builder("ledgerforge.ledger.verification.latency")
                .description("Execution latency for ledger verification scans")
                .tag("result", verification.allChecksPassed() ? "pass" : "fail")
                .register(meterRegistry));
    }

    private void registerCategoryGauge(String category) {
        AtomicInteger count = new AtomicInteger();
        categoryCounts.put(category, count);
        Gauge.builder("ledgerforge.ledger.verification.last.finding_count", count, AtomicInteger::get)
                .description("Finding counts from the most recent ledger verification run")
                .tag("category", category)
                .register(meterRegistry);
    }
}
