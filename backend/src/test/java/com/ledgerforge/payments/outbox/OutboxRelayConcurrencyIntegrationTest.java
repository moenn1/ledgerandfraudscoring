package com.ledgerforge.payments.outbox;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentService;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "ledgerforge.outbox.relay-enabled=false")
class OutboxRelayConcurrencyIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @MockBean
    private DomainEventPublisher domainEventPublisher;

    @Test
    void concurrentRelayAttempts_claimAndPublishAnEventOnlyOnce() throws Exception {
        UUID payerId = createAccount("relay-payer-" + UUID.randomUUID(), "USD");
        UUID payeeId = createAccount("relay-payee-" + UUID.randomUUID(), "USD");

        String idempotencyKey = "relay-create-" + UUID.randomUUID();
        PaymentIntentEntity payment = paymentService.createWithIdempotency(
                new CreatePaymentRequest(payerId, payeeId, null, 8_500L, "USD", idempotencyKey),
                idempotencyKey,
                "corr-relay-" + UUID.randomUUID()
        );

        OutboxEventEntity pending = outboxEventRepository.findAllByOrderByCreatedAtAsc().stream()
                .filter(event -> "payment.created".equals(event.getEventType()) && payment.getId().equals(event.getAggregateId()))
                .findFirst()
                .orElseThrow();

        CountDownLatch publishStarted = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishStarted.countDown();
            assertThat(releasePublish.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(domainEventPublisher).publish(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Instant asOf = pending.getCreatedAt().plusSeconds(1);
            Future<Integer> firstRun = executor.submit(() -> outboxRelayService.relayDueEvents(asOf));
            assertThat(publishStarted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> secondRun = executor.submit(() -> outboxRelayService.relayDueEvents(asOf));
            releasePublish.countDown();

            assertThat(firstRun.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(secondRun.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            executor.shutdownNow();
        }

        OutboxEventEntity published = outboxEventRepository.findById(pending.getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getAttemptCount()).isEqualTo(1);
        verify(domainEventPublisher, times(1)).publish(any());
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
