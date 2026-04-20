package com.ledgerforge.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgerForgePaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerForgePaymentsApplication.class, args);
    }
}
