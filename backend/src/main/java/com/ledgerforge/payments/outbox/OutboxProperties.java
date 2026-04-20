package com.ledgerforge.payments.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ledgerforge.outbox")
public class OutboxProperties {

    private String destination = "ledgerforge.audit-events";
    private boolean relayEnabled = true;
    private int relayBatchSize = 25;
    private long relayDelayMs = 5_000L;
    private int maxAttempts = 5;
    private long baseDelayMs = 5_000L;
    private long maxDelayMs = 300_000L;

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public int getRelayBatchSize() {
        return relayBatchSize;
    }

    public void setRelayBatchSize(int relayBatchSize) {
        this.relayBatchSize = relayBatchSize;
    }

    public long getRelayDelayMs() {
        return relayDelayMs;
    }

    public void setRelayDelayMs(long relayDelayMs) {
        this.relayDelayMs = relayDelayMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    public void setBaseDelayMs(long baseDelayMs) {
        this.baseDelayMs = baseDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMaxDelayMs(long maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }
}
