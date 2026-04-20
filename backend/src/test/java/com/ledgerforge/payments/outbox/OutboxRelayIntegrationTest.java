package com.ledgerforge.payments.outbox;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentService;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "ledgerforge.outbox.relay-enabled=false")
@Transactional
class OutboxRelayIntegrationTest {

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
    void paymentLifecycle_enqueuesPaymentAndLedgerEvents() {
        UUID payerId = createAccount("outbox-payer-1", "USD");
        UUID payeeId = createAccount("outbox-payee-1", "USD");

        PaymentIntentEntity payment = paymentService.createWithIdempotency(
                new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", "outbox-create-1"),
                "outbox-create-1",
                "corr-outbox-flow"
        );

        paymentService.confirm(payment.getId(), null, "outbox-confirm-1", "corr-outbox-flow");
        paymentService.capture(payment.getId(), "outbox-capture-1", "corr-outbox-flow");

        List<OutboxEventEntity> events = outboxEventRepository.findAllByOrderByCreatedAtAsc();
        assertThat(events).extracting(OutboxEventEntity::getEventType).containsExactly(
                "payment.created",
                "ledger.journal.committed",
                "payment.reserved",
                "ledger.journal.committed",
                "payment.captured"
        );
        assertThat(events).extracting(OutboxEventEntity::getCorrelationId)
                .containsOnly("corr-outbox-flow");
        assertThat(events).filteredOn(event -> event.getEventType().startsWith("payment."))
                .extracting(OutboxEventEntity::getIdempotencyKey)
                .containsExactly(masked("outbox-create-1"), masked("outbox-confirm-1"), masked("outbox-capture-1"));
    }

    @Test
    void relayRetriesFailuresAndPreservesStableEventId() {
        UUID payerId = createAccount("outbox-payer-2", "USD");
        UUID payeeId = createAccount("outbox-payee-2", "USD");

        paymentService.createWithIdempotency(
                new CreatePaymentRequest(payerId, payeeId, null, 5_000L, "USD", "outbox-retry-create"),
                "outbox-retry-create",
                "corr-outbox-retry"
        );

        OutboxEventEntity pending = outboxEventRepository.findAllByOrderByCreatedAtAsc().get(0);
        doThrow(new IllegalStateException("broker unavailable"))
                .doNothing()
                .when(domainEventPublisher)
                .publish(any());

        int firstRun = outboxRelayService.relayDueEvents(pending.getCreatedAt().plusSeconds(1));
        assertThat(firstRun).isZero();

        OutboxEventEntity afterFailure = outboxEventRepository.findById(pending.getId()).orElseThrow();
        assertThat(afterFailure.getPublishedAt()).isNull();
        assertThat(afterFailure.getAttemptCount()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).contains("broker unavailable");
        assertThat(afterFailure.getAvailableAt()).isAfter(afterFailure.getCreatedAt());

        int secondRun = outboxRelayService.relayDueEvents(afterFailure.getAvailableAt().plusSeconds(1));
        assertThat(secondRun).isEqualTo(1);

        OutboxEventEntity afterSuccess = outboxEventRepository.findById(pending.getId()).orElseThrow();
        assertThat(afterSuccess.getPublishedAt()).isNotNull();
        assertThat(afterSuccess.getAttemptCount()).isEqualTo(2);
        assertThat(afterSuccess.getLastError()).isNull();

        ArgumentCaptor<DomainEventEnvelope> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(domainEventPublisher, times(2)).publish(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DomainEventEnvelope::eventId)
                .containsOnly(pending.getId());
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }

    private String masked(String value) {
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }
}
