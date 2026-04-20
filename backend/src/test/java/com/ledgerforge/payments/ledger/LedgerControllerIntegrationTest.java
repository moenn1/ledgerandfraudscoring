package com.ledgerforge.payments.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
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

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
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
}
