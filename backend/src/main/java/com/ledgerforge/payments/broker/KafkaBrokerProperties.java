package com.ledgerforge.payments.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerforge.kafka")
public class KafkaBrokerProperties {

    private boolean enabled;
    private final Topics topics = new Topics();
    private final Producer producer = new Producer();
    private final Consumer consumer = new Consumer();
    private final Listener notifications = new Listener();
    private final Listener deadLetter = new Listener();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Topics getTopics() {
        return topics;
    }

    public Producer getProducer() {
        return producer;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public Listener getNotifications() {
        return notifications;
    }

    public Listener getDeadLetter() {
        return deadLetter;
    }

    public static class Topics {
        private String domainEvents = "ledgerforge.domain-events";
        private String domainEventsDlt = "ledgerforge.domain-events.dlt";

        public String getDomainEvents() {
            return domainEvents;
        }

        public void setDomainEvents(String domainEvents) {
            this.domainEvents = domainEvents;
        }

        public String getDomainEventsDlt() {
            return domainEventsDlt;
        }

        public void setDomainEventsDlt(String domainEventsDlt) {
            this.domainEventsDlt = domainEventsDlt;
        }
    }

    public static class Producer {
        private long sendTimeoutMs = 5000;

        public long getSendTimeoutMs() {
            return sendTimeoutMs;
        }

        public void setSendTimeoutMs(long sendTimeoutMs) {
            this.sendTimeoutMs = sendTimeoutMs;
        }
    }

    public static class Consumer {
        private long maxAttempts = 3;
        private long backoffMs = 1000;

        public long getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(long maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(long backoffMs) {
            this.backoffMs = backoffMs;
        }
    }

    public static class Listener {
        private String consumerGroupId;

        public String getConsumerGroupId() {
            return consumerGroupId;
        }

        public void setConsumerGroupId(String consumerGroupId) {
            this.consumerGroupId = consumerGroupId;
        }
    }
}
