package com.ledgerforge.payments.broker;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "ledgerforge.kafka", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaBrokerProperties.class)
public class KafkaBrokerConfig {

    @Bean
    public NewTopic ledgerforgeDomainEventsTopic(KafkaBrokerProperties properties) {
        return TopicBuilder.name(properties.getTopics().getDomainEvents())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic ledgerforgeDomainEventsDltTopic(KafkaBrokerProperties properties) {
        return TopicBuilder.name(properties.getTopics().getDomainEventsDlt())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean(name = "ledgerforgeKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> ledgerforgeKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaBrokerProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(properties.getTopics().getDomainEventsDlt(), record.partition())
        );

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, (int) properties.getConsumer().getMaxAttempts() - 1));
        backOff.setInitialInterval(properties.getConsumer().getBackoffMs());
        backOff.setMultiplier(1.0);
        backOff.setMaxInterval(properties.getConsumer().getBackoffMs());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(DeserializationException.class, IllegalArgumentException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean(name = "ledgerforgeDltListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> ledgerforgeDltListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<String, String> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        return factory;
    }
}
