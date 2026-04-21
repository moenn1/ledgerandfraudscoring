package com.ledgerforge.payments.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.audit.AuditEventEntity;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.outbox.OutboxEventEntity;
import com.ledgerforge.payments.outbox.OutboxEventRepository;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentIntentRepository;
import com.ledgerforge.payments.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LedgerControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void replayAccount_returnsRunningBalanceFromImmutableEntries() throws Exception {
        UUID payerId = createAccount("replay-payer", "USD");
        UUID payeeId = createAccount("replay-payee", "USD");

        ledgerService.createJournal(new CreateJournalRequest(
                JournalType.PAYMENT,
                "replay-payment-1",
                java.util.List.of(
                        new CreateLedgerLegRequest(payerId, LedgerDirection.DEBIT, new BigDecimal("10.00"), "USD"),
                        new CreateLedgerLegRequest(payeeId, LedgerDirection.CREDIT, new BigDecimal("10.00"), "USD")
                )
        ));

        ledgerService.createJournal(new CreateJournalRequest(
                JournalType.REFUND,
                "replay-refund-1",
                java.util.List.of(
                        new CreateLedgerLegRequest(payeeId, LedgerDirection.DEBIT, new BigDecimal("4.00"), "USD"),
                        new CreateLedgerLegRequest(payerId, LedgerDirection.CREDIT, new BigDecimal("4.00"), "USD")
                )
        ));

        String response = mockMvc.perform(get("/api/ledger/replay/accounts/{accountId}", payerId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("accountId").asText()).isEqualTo(payerId.toString());
        assertThat(json.get("currency").asText()).isEqualTo("USD");
        assertThat(json.get("entryCount").asInt()).isEqualTo(2);
        assertThat(json.get("projectedBalance").decimalValue()).isEqualByComparingTo("-6.00");
        assertThat(json.at("/entries/0/referenceId").asText()).isEqualTo("replay-payment-1");
        assertThat(json.at("/entries/0/signedImpact").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(json.at("/entries/0/runningBalance").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(json.at("/entries/1/referenceId").asText()).isEqualTo("replay-refund-1");
        assertThat(json.at("/entries/1/signedImpact").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(json.at("/entries/1/runningBalance").decimalValue()).isEqualByComparingTo("-6.00");
    }

    @Test
    void verification_flagsBrokenJournalsAndPaymentLifecycleMismatches() throws Exception {
        UUID usdAccountId = createAccount("verify-usd", "USD");
        UUID eurAccountId = createAccount("verify-eur", "EUR");

        JournalTransactionEntity brokenJournal = new JournalTransactionEntity();
        brokenJournal.setType(JournalType.PAYMENT);
        brokenJournal.setStatus(JournalStatus.COMMITTED);
        brokenJournal.setReferenceId("manual-bad-journal");
        brokenJournal = journalTransactionRepository.save(brokenJournal);

        ledgerEntryRepository.save(entry(brokenJournal, usdAccountId, LedgerDirection.DEBIT, "10.00", "USD"));
        ledgerEntryRepository.save(entry(brokenJournal, eurAccountId, LedgerDirection.CREDIT, "9.00", "EUR"));

        PaymentIntentEntity mismatchedPayment = new PaymentIntentEntity();
        mismatchedPayment.setPayerAccountId(usdAccountId);
        mismatchedPayment.setPayeeAccountId(eurAccountId);
        mismatchedPayment.setAmount(new BigDecimal("25.00"));
        mismatchedPayment.setCurrency("USD");
        mismatchedPayment.setStatus(PaymentStatus.CAPTURED);
        mismatchedPayment.setIdempotencyKey("verification-mismatch-" + UUID.randomUUID());
        mismatchedPayment = paymentIntentRepository.save(mismatchedPayment);

        String response = mockMvc.perform(get("/api/ledger/verification"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("allChecksPassed").asBoolean()).isFalse();

        JsonNode unbalanced = findByField(json.get("unbalancedJournals"), "journalId", brokenJournal.getId().toString());
        assertThat(unbalanced).isNotNull();
        assertThat(unbalanced.get("referenceId").asText()).isEqualTo("manual-bad-journal");
        assertThat(unbalanced.get("netAmount").decimalValue()).isEqualByComparingTo("-1");

        JsonNode mixedCurrency = findByField(json.get("mixedCurrencyJournals"), "journalId", brokenJournal.getId().toString());
        assertThat(mixedCurrency).isNotNull();
        assertThat(mixedCurrency.get("currencies")).extracting(JsonNode::asText).containsExactly("EUR", "USD");

        JsonNode mismatch = findByField(json.get("paymentLifecycleMismatches"), "paymentId", mismatchedPayment.getId().toString());
        assertThat(mismatch).isNotNull();
        assertThat(mismatch.get("paymentStatus").asText()).isEqualTo("CAPTURED");
        assertThat(mismatch.get("actualJournalTypes")).isEmpty();
        assertThat(mismatch.get("missingJournalTypes")).extracting(JsonNode::asText).containsExactly("CAPTURE", "RESERVE");
        assertThat(mismatch.get("unexpectedJournalTypes")).isEmpty();
    }

    @Test
    void verification_flagsMissingAuditAndOutboxEventsForPaymentMutation() throws Exception {
        UUID payerAccountId = createAccount("missing-event-payer", "USD");
        UUID payeeAccountId = createAccount("missing-event-payee", "USD");
        UUID holdingAccountId = createAccount("missing-event-holding", "USD");

        PaymentIntentEntity payment = createPayment(payerAccountId, payeeAccountId, new BigDecimal("30.00"), PaymentStatus.RESERVED);

        JournalTransactionEntity reserveJournal = ledgerService.postJournal(
                JournalType.RESERVE,
                "payment:" + payment.getId() + ":reserve",
                java.util.List.of(
                        new LedgerLeg(payerAccountId, LedgerDirection.DEBIT, new BigDecimal("30.00"), "USD"),
                        new LedgerLeg(holdingAccountId, LedgerDirection.CREDIT, new BigDecimal("30.00"), "USD")
                )
        );

        String response = mockMvc.perform(get("/api/ledger/verification"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);

        JsonNode auditFinding = findMutationEventFinding(json, payment.getId().toString(), "reserve", "AUDIT");
        assertThat(auditFinding).isNotNull();
        assertThat(auditFinding.get("journalType").asText()).isEqualTo("RESERVE");
        assertThat(auditFinding.get("eventType").asText()).isEqualTo("payment.reserved");
        assertThat(auditFinding.get("ledgerMutationCount").asInt()).isEqualTo(1);
        assertThat(auditFinding.get("observedEventCount").asInt()).isEqualTo(0);
        assertThat(auditFinding.get("missingEventCount").asInt()).isEqualTo(1);
        assertThat(auditFinding.get("duplicateEventCount").asInt()).isEqualTo(0);
        assertThat(auditFinding.get("journalIds")).extracting(JsonNode::asText)
                .containsExactly(reserveJournal.getId().toString());

        JsonNode outboxFinding = findMutationEventFinding(json, payment.getId().toString(), "reserve", "OUTBOX");
        assertThat(outboxFinding).isNotNull();
        assertThat(outboxFinding.get("missingEventCount").asInt()).isEqualTo(1);
        assertThat(outboxFinding.get("duplicateEventCount").asInt()).isEqualTo(0);
    }

    @Test
    void verification_flagsDuplicateAuditAndOutboxEventsForPaymentMutation() throws Exception {
        UUID payerAccountId = createAccount("duplicate-event-payer", "USD");
        UUID payeeAccountId = createAccount("duplicate-event-payee", "USD");
        UUID holdingAccountId = createAccount("duplicate-event-holding", "USD");

        PaymentIntentEntity payment = createPayment(payerAccountId, payeeAccountId, new BigDecimal("45.00"), PaymentStatus.RESERVED);

        JournalTransactionEntity reserveJournal = ledgerService.postJournal(
                JournalType.RESERVE,
                "payment:" + payment.getId() + ":reserve",
                java.util.List.of(
                        new LedgerLeg(payerAccountId, LedgerDirection.DEBIT, new BigDecimal("45.00"), "USD"),
                        new LedgerLeg(holdingAccountId, LedgerDirection.CREDIT, new BigDecimal("45.00"), "USD")
                )
        );

        auditEventRepository.save(auditEvent(payment.getId(), reserveJournal.getId(), "payment.reserved"));
        auditEventRepository.save(auditEvent(payment.getId(), reserveJournal.getId(), "payment.reserved"));
        outboxEventRepository.save(outboxEvent(payment.getId(), reserveJournal.getId(), "payment.reserved"));
        outboxEventRepository.save(outboxEvent(payment.getId(), reserveJournal.getId(), "payment.reserved"));

        String response = mockMvc.perform(get("/api/ledger/verification"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);

        JsonNode auditFinding = findMutationEventFinding(json, payment.getId().toString(), "reserve", "AUDIT");
        assertThat(auditFinding).isNotNull();
        assertThat(auditFinding.get("observedEventCount").asInt()).isEqualTo(2);
        assertThat(auditFinding.get("missingEventCount").asInt()).isEqualTo(0);
        assertThat(auditFinding.get("duplicateEventCount").asInt()).isEqualTo(1);

        JsonNode outboxFinding = findMutationEventFinding(json, payment.getId().toString(), "reserve", "OUTBOX");
        assertThat(outboxFinding).isNotNull();
        assertThat(outboxFinding.get("observedEventCount").asInt()).isEqualTo(2);
        assertThat(outboxFinding.get("missingEventCount").asInt()).isEqualTo(0);
        assertThat(outboxFinding.get("duplicateEventCount").asInt()).isEqualTo(1);
    }

    @Test
    void verification_flagsDuplicateReserveJournalsEvenWhenPaymentStatusMatches() throws Exception {
        UUID payerAccountId = createAccount("duplicate-journal-payer", "USD");
        UUID payeeAccountId = createAccount("duplicate-journal-payee", "USD");
        UUID holdingAccountId = createAccount("duplicate-journal-holding", "USD");

        PaymentIntentEntity payment = createPayment(payerAccountId, payeeAccountId, new BigDecimal("60.00"), PaymentStatus.RESERVED);
        String firstReferenceId = "payment:" + payment.getId() + ":reserve";
        String duplicateReferenceId = firstReferenceId + ":duplicate";

        JournalTransactionEntity firstReserveJournal = paymentJournal(JournalType.RESERVE, firstReferenceId);
        firstReserveJournal = journalTransactionRepository.save(firstReserveJournal);
        ledgerEntryRepository.save(entry(firstReserveJournal, payerAccountId, LedgerDirection.DEBIT, "60.00", "USD"));
        ledgerEntryRepository.save(entry(firstReserveJournal, holdingAccountId, LedgerDirection.CREDIT, "60.00", "USD"));

        JournalTransactionEntity duplicateReserveJournal = paymentJournal(JournalType.RESERVE, duplicateReferenceId);
        duplicateReserveJournal = journalTransactionRepository.save(duplicateReserveJournal);
        ledgerEntryRepository.save(entry(duplicateReserveJournal, payerAccountId, LedgerDirection.DEBIT, "60.00", "USD"));
        ledgerEntryRepository.save(entry(duplicateReserveJournal, holdingAccountId, LedgerDirection.CREDIT, "60.00", "USD"));

        auditEventRepository.save(auditEvent(payment.getId(), firstReserveJournal.getId(), "payment.reserved"));
        auditEventRepository.save(auditEvent(payment.getId(), duplicateReserveJournal.getId(), "payment.reserved"));
        outboxEventRepository.save(outboxEvent(payment.getId(), firstReserveJournal.getId(), "payment.reserved"));
        outboxEventRepository.save(outboxEvent(payment.getId(), duplicateReserveJournal.getId(), "payment.reserved"));

        String response = mockMvc.perform(get("/api/ledger/verification"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);

        JsonNode duplicateJournalFinding = findDuplicatePaymentJournalFinding(
                json,
                payment.getId().toString(),
                "reserve"
        );

        assertThat(json.get("allChecksPassed").asBoolean()).isFalse();
        assertThat(json.get("issueCount").asInt()).isEqualTo(1);
        assertThat(json.get("paymentLifecycleMismatches").size()).isZero();
        assertThat(duplicateJournalFinding).isNotNull();
        assertThat(duplicateJournalFinding.get("journalType").asText()).isEqualTo("RESERVE");
        assertThat(duplicateJournalFinding.get("journalCount").asInt()).isEqualTo(2);
        assertThat(duplicateJournalFinding.get("referenceIds")).extracting(JsonNode::asText)
                .containsExactly(firstReferenceId, duplicateReferenceId);
        assertThat(duplicateJournalFinding.get("journalIds")).extracting(JsonNode::asText)
                .containsExactly(firstReserveJournal.getId().toString(), duplicateReserveJournal.getId().toString());
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }

    private PaymentIntentEntity createPayment(UUID payerAccountId,
                                              UUID payeeAccountId,
                                              BigDecimal amount,
                                              PaymentStatus status) {
        PaymentIntentEntity payment = new PaymentIntentEntity();
        payment.setPayerAccountId(payerAccountId);
        payment.setPayeeAccountId(payeeAccountId);
        payment.setAmount(amount);
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setIdempotencyKey("verification-payment-" + UUID.randomUUID());
        return paymentIntentRepository.save(payment);
    }

    private AuditEventEntity auditEvent(UUID paymentId, UUID journalId, String eventType) {
        AuditEventEntity event = new AuditEventEntity();
        event.setPaymentId(paymentId);
        event.setJournalId(journalId);
        event.setEventType(eventType);
        return event;
    }

    private OutboxEventEntity outboxEvent(UUID paymentId, UUID journalId, String eventType) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setPaymentId(paymentId);
        event.setJournalId(journalId);
        event.setEventType(eventType);
        return event;
    }

    private JournalTransactionEntity paymentJournal(JournalType type, String referenceId) {
        JournalTransactionEntity journal = new JournalTransactionEntity();
        journal.setType(type);
        journal.setStatus(JournalStatus.COMMITTED);
        journal.setReferenceId(referenceId);
        return journal;
    }

    private LedgerEntryEntity entry(
            JournalTransactionEntity journal,
            UUID accountId,
            LedgerDirection direction,
            String amount,
            String currency
    ) {
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setJournal(journal);
        entry.setAccountId(accountId);
        entry.setDirection(direction);
        entry.setAmount(new BigDecimal(amount));
        entry.setCurrency(currency);
        return entry;
    }

    private JsonNode findByField(JsonNode arrayNode, String fieldName, String expectedValue) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        Iterator<JsonNode> iterator = arrayNode.elements();
        while (iterator.hasNext()) {
            JsonNode candidate = iterator.next();
            if (expectedValue.equals(candidate.path(fieldName).asText())) {
                return candidate;
            }
        }
        return null;
    }

    private JsonNode findMutationEventFinding(JsonNode json, String paymentId, String action, String eventSink) {
        JsonNode findings = json.get("mutationEventReconciliationFindings");
        if (findings == null || !findings.isArray()) {
            return null;
        }
        Iterator<JsonNode> iterator = findings.elements();
        while (iterator.hasNext()) {
            JsonNode candidate = iterator.next();
            if (paymentId.equals(candidate.path("paymentId").asText())
                    && action.equals(candidate.path("action").asText())
                    && eventSink.equals(candidate.path("eventSink").asText())) {
                return candidate;
            }
        }
        return null;
    }

    private JsonNode findDuplicatePaymentJournalFinding(JsonNode json, String paymentId, String action) {
        JsonNode findings = json.get("duplicatePaymentJournals");
        if (findings == null || !findings.isArray()) {
            return null;
        }
        Iterator<JsonNode> iterator = findings.elements();
        while (iterator.hasNext()) {
            JsonNode candidate = iterator.next();
            if (paymentId.equals(candidate.path("paymentId").asText())
                    && action.equals(candidate.path("action").asText())) {
                return candidate;
            }
        }
        return null;
    }
}
