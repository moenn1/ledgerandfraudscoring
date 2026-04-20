package com.ledgerforge.payments.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxRelayService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxService outboxService;
    private final DomainEventPublisher domainEventPublisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseDuration;
    private final boolean relayEnabled;
    private final String relayOwner;

    public OutboxRelayService(OutboxEventRepository outboxEventRepository,
                              OutboxService outboxService,
                              DomainEventPublisher domainEventPublisher,
                              @Value("${ledgerforge.outbox.batch-size:25}") int batchSize,
                              @Value("${ledgerforge.outbox.lease-duration-ms:30000}") long leaseDurationMs,
                              @Value("${ledgerforge.outbox.relay-enabled:true}") boolean relayEnabled) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxService = outboxService;
        this.domainEventPublisher = domainEventPublisher;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofMillis(leaseDurationMs);
        this.relayEnabled = relayEnabled;
        this.clock = Clock.systemUTC();
        this.relayOwner = "relay-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${ledgerforge.outbox.poll-delay-ms:5000}")
    public void scheduledRelay() {
        if (!relayEnabled) {
            return;
        }
        relayDueEvents(clock.instant());
    }

    public int relayDueEvents() {
        return relayDueEvents(clock.instant());
    }

    public int relayDueEvents(Instant asOf) {
        List<UUID> candidateIds = outboxEventRepository.findRelayCandidateIds(
                asOf,
                PageRequest.of(0, batchSize)
        );

        int publishedCount = 0;
        for (UUID candidateId : candidateIds) {
            if (outboxEventRepository.claimForDelivery(candidateId, asOf, relayOwner, asOf.plus(leaseDuration)) == 0) {
                continue;
            }

            OutboxEventEntity event = outboxEventRepository.findById(candidateId)
                    .orElseThrow(() -> new IllegalStateException("Claimed outbox event disappeared: " + candidateId));

            try {
                domainEventPublisher.publish(outboxService.toEnvelope(event));
                outboxEventRepository.markPublished(candidateId, relayOwner, asOf);
                publishedCount++;
            } catch (RuntimeException ex) {
                outboxEventRepository.markFailed(
                        candidateId,
                        relayOwner,
                        asOf.plus(retryDelay(event.getAttemptCount())),
                        abbreviate(ex)
                );
            }
        }
        return publishedCount;
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 6));
        long multiplier = 1L << exponent;
        return Duration.ofSeconds(5L * multiplier);
    }

    private String abbreviate(RuntimeException ex) {
        String message = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage());
        if (message.length() <= 512) {
            return message;
        }
        return message.substring(0, 509) + "...";
    }
}
