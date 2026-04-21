package com.ledgerforge.payments.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerforge.outbox")
public class OutboxProperties {

    private boolean relayEnabled = true;
    private long relayFixedDelayMs = 5_000;
    private int batchSize = 25;
    private long claimTimeoutMs = 30_000;
    private long baseDelayMs = 1_000;
    private long maxDelayMs = 60_000;
    private int maxAttempts = 5;

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public long getRelayFixedDelayMs() {
        return relayFixedDelayMs;
    }

    public void setRelayFixedDelayMs(long relayFixedDelayMs) {
        this.relayFixedDelayMs = relayFixedDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getClaimTimeoutMs() {
        return claimTimeoutMs;
    }

    public void setClaimTimeoutMs(long claimTimeoutMs) {
        this.claimTimeoutMs = claimTimeoutMs;
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

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
