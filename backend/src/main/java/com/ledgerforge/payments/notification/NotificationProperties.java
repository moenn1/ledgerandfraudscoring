package com.ledgerforge.payments.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ledgerforge.notifications")
public class NotificationProperties {

    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 5000;
    private int baseRetryDelaySeconds = 30;
    private int callbackSkewSeconds = 300;
    private int maxBatchSize = 25;
    private int maxResponseBodyChars = 2000;
    private int defaultMaxAttempts = 3;

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getBaseRetryDelaySeconds() {
        return baseRetryDelaySeconds;
    }

    public void setBaseRetryDelaySeconds(int baseRetryDelaySeconds) {
        this.baseRetryDelaySeconds = baseRetryDelaySeconds;
    }

    public int getCallbackSkewSeconds() {
        return callbackSkewSeconds;
    }

    public void setCallbackSkewSeconds(int callbackSkewSeconds) {
        this.callbackSkewSeconds = callbackSkewSeconds;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxResponseBodyChars() {
        return maxResponseBodyChars;
    }

    public void setMaxResponseBodyChars(int maxResponseBodyChars) {
        this.maxResponseBodyChars = maxResponseBodyChars;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }
}
