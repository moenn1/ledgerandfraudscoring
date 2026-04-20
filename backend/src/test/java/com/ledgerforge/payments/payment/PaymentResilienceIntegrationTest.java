package com.ledgerforge.payments.payment;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.outbox.OutboxEventEntity;
import com.ledgerforge.payments.outbox.OutboxEventRepository;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "ledgerforge.outbox.relay-enabled=false")
class PaymentResilienceIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentIntentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void concurrentCreateWithSameIdempotencyKey_returnsOnePaymentAndOneSideEffectSet() throws Exception {
        UUID payerId = createAccount("resilience-payer-" + UUID.randomUUID(), "USD");
        UUID payeeId = createAccount("resilience-payee-" + UUID.randomUUID(), "USD");
        String idempotencyKey = "resilience-create-" + UUID.randomUUID();
        String correlationId = "corr-" + idempotencyKey;
        CreatePaymentRequest request = new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", idempotencyKey);

        int attempts = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        try {
            Callable<PaymentIntentEntity> createTask =
                    () -> {
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return paymentService.createWithIdempotency(request, idempotencyKey, correlationId);
                    };

            List<Future<PaymentIntentEntity>> futures = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(createTask));
            }
            start.countDown();

            List<PaymentIntentEntity> results = new ArrayList<>();
            for (Future<PaymentIntentEntity> future : futures) {
                results.add(resolve(future));
            }
            PaymentIntentEntity stored = paymentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();

            assertThat(results).extracting(PaymentIntentEntity::getId).containsOnly(stored.getId());
            assertThat(outboxEventRepository.findAllByOrderByCreatedAtAsc()).filteredOn(event ->
                    "payment.created".equals(event.getEventType())
                            && stored.getId().equals(event.getAggregateId())
            ).hasSize(1);
            assertThat(auditEventRepository.findAll()).filteredOn(event ->
                    "payment.created".equals(event.getEventType())
                            && stored.getId().equals(event.getPaymentId())
            ).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private PaymentIntentEntity resolve(Future<PaymentIntentEntity> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
