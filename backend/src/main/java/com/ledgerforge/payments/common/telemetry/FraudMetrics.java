package com.ledgerforge.payments.common.telemetry;

import com.ledgerforge.payments.fraud.ReviewCaseEntity;
import com.ledgerforge.payments.fraud.ReviewCaseRepository;
import com.ledgerforge.payments.fraud.ReviewCaseStatus;
import com.ledgerforge.payments.fraud.api.ReviewDecisionRequest;
import com.ledgerforge.payments.payment.RiskDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class FraudMetrics {

    private final ReviewCaseRepository reviewCaseRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public FraudMetrics(ReviewCaseRepository reviewCaseRepository,
                        MeterRegistry meterRegistry,
                        Clock clock) {
        this.reviewCaseRepository = reviewCaseRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;

        Gauge.builder("ledgerforge.fraud.review.queue.depth", reviewCaseRepository, repository -> repository.countByStatus(ReviewCaseStatus.OPEN))
                .description("Number of open fraud review cases waiting for an operator decision")
                .register(meterRegistry);
        Gauge.builder("ledgerforge.fraud.review.queue.age.seconds", this::oldestOpenReviewAgeSeconds)
                .description("Age in seconds of the oldest open fraud review case")
                .register(meterRegistry);
    }

    public Timer.Sample startScoring() {
        return Timer.start(meterRegistry);
    }

    public void recordScoringDecision(RiskDecision decision, Timer.Sample sample) {
        sample.stop(Timer.builder("ledgerforge.fraud.scoring.latency")
                .description("Execution latency for fraud scoring decisions")
                .tag("decision", decision.name())
                .register(meterRegistry));

        Counter.builder("ledgerforge.fraud.scoring.outcome.total")
                .description("Fraud scoring decisions emitted during payment confirmation")
                .tag("decision", decision.name())
                .register(meterRegistry)
                .increment();
    }

    public void recordScoringFailure(Timer.Sample sample) {
        sample.stop(Timer.builder("ledgerforge.fraud.scoring.latency")
                .description("Execution latency for fraud scoring decisions")
                .tag("decision", "ERROR")
                .register(meterRegistry));

        Counter.builder("ledgerforge.fraud.scoring.failure.total")
                .description("Fraud scoring attempts that failed before a decision was persisted")
                .register(meterRegistry)
                .increment();
    }

    public void recordReviewCaseOpened() {
        Counter.builder("ledgerforge.fraud.review.case.opened")
                .description("Fraud review cases opened from payment confirmation decisions")
                .register(meterRegistry)
                .increment();
    }

    public void recordReviewDecision(ReviewCaseEntity reviewCase, ReviewDecisionRequest.ReviewDecision decision) {
        Counter.builder("ledgerforge.fraud.review.case.decided")
                .description("Fraud review decisions made by operators")
                .tag("decision", decision.name())
                .register(meterRegistry)
                .increment();

        Duration openDuration = Duration.between(reviewCase.getCreatedAt(), Instant.now(clock));
        if (!openDuration.isNegative()) {
            Timer.builder("ledgerforge.fraud.review.case.open_duration")
                    .description("Time a fraud review case spent open before a decision")
                    .tag("decision", decision.name())
                    .register(meterRegistry)
                    .record(openDuration);
        }
    }

    private double oldestOpenReviewAgeSeconds() {
        return reviewCaseRepository.findOldestCreatedAtByStatus(ReviewCaseStatus.OPEN)
                .map(createdAt -> (double) Duration.between(createdAt, Instant.now(clock)).toSeconds())
                .orElse(0.0d);
    }
}
