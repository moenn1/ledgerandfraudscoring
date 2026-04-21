package com.ledgerforge.payments.common.telemetry;

import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.RiskDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public PaymentMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public Timer.Sample startOperation() {
        return Timer.start(meterRegistry);
    }

    public void recordOperation(String operation, String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder("ledgerforge.payment.operation.latency")
                .description("Execution latency for payment domain operations")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    public void recordOutcome(PaymentIntentEntity payment) {
        Counter.builder("ledgerforge.payment.outcome.total")
                .description("Persisted payment lifecycle outcomes")
                .tag("status", payment.getStatus().name())
                .tag("risk_decision", riskDecisionTag(payment.getRiskDecision()))
                .register(meterRegistry)
                .increment();

        Duration lifecycleDuration = Duration.between(payment.getCreatedAt(), Instant.now(clock));
        if (!lifecycleDuration.isNegative()) {
            Timer.builder("ledgerforge.payment.lifecycle.time_to_status")
                    .description("Time from payment creation until a persisted lifecycle status is reached")
                    .tag("status", payment.getStatus().name())
                    .tag("risk_decision", riskDecisionTag(payment.getRiskDecision()))
                    .register(meterRegistry)
                    .record(lifecycleDuration);
        }
    }

    private String riskDecisionTag(RiskDecision riskDecision) {
        return riskDecision == null ? "NONE" : riskDecision.name();
    }
}
