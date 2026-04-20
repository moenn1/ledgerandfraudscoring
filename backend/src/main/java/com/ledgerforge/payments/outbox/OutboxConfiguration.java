package com.ledgerforge.payments.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OutboxConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
