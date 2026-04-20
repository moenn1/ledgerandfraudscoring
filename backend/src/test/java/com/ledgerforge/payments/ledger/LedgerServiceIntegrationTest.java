package com.ledgerforge.payments.ledger;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class LedgerServiceIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void createsBalancedJournalAndProjectsBalances() {
        AccountEntity payer = createAccount("payer-1", "USD");
        AccountEntity payee = createAccount("payee-1", "USD");

        JournalResponse journal = ledgerService.createJournal(
                new CreateJournalRequest(
                        JournalType.PAYMENT,
                        "payment-001",
                        List.of(
                                new CreateLedgerLegRequest(payer.getId(), LedgerDirection.DEBIT, new BigDecimal("100.00"), "USD"),
                                new CreateLedgerLegRequest(payee.getId(), LedgerDirection.CREDIT, new BigDecimal("100.00"), "USD")
                        )
                )
        );

        assertThat(journal.entries()).hasSize(2);
        assertThat(accountService.balance(payer.getId()).balance()).isEqualByComparingTo("-100.0000");
        assertThat(accountService.balance(payee.getId()).balance()).isEqualByComparingTo("100.0000");
        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void rejectsUnbalancedJournal() {
        AccountEntity payer = createAccount("payer-2", "USD");
        AccountEntity payee = createAccount("payee-2", "USD");

        ApiException exception = assertThrows(ApiException.class, () -> ledgerService.createJournal(
                new CreateJournalRequest(
                        JournalType.PAYMENT,
                        "payment-unbalanced",
                        List.of(
                                new CreateLedgerLegRequest(payer.getId(), LedgerDirection.DEBIT, new BigDecimal("100.00"), "USD"),
                                new CreateLedgerLegRequest(payee.getId(), LedgerDirection.CREDIT, new BigDecimal("99.00"), "USD")
                        )
                )
        ));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void treatsDuplicateReferenceWithSamePayloadAsIdempotent() {
        AccountEntity payer = createAccount("payer-3", "USD");
        AccountEntity payee = createAccount("payee-3", "USD");

        CreateJournalRequest request = new CreateJournalRequest(
                JournalType.PAYMENT,
                "payment-idempotent-001",
                List.of(
                        new CreateLedgerLegRequest(payer.getId(), LedgerDirection.DEBIT, new BigDecimal("25.00"), "USD"),
                        new CreateLedgerLegRequest(payee.getId(), LedgerDirection.CREDIT, new BigDecimal("25.00"), "USD")
                )
        );

        JournalResponse first = ledgerService.createJournal(request);
        JournalResponse second = ledgerService.createJournal(request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(journalTransactionRepository.count()).isEqualTo(1L);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2L);
    }

    @Test
    void rejectsDuplicateReferenceWhenPayloadDiffers() {
        AccountEntity payer = createAccount("payer-4", "USD");
        AccountEntity payee = createAccount("payee-4", "USD");

        ledgerService.createJournal(
                new CreateJournalRequest(
                        JournalType.PAYMENT,
                        "payment-conflict-001",
                        List.of(
                                new CreateLedgerLegRequest(payer.getId(), LedgerDirection.DEBIT, new BigDecimal("40.00"), "USD"),
                                new CreateLedgerLegRequest(payee.getId(), LedgerDirection.CREDIT, new BigDecimal("40.00"), "USD")
                        )
                )
        );

        ApiException exception = assertThrows(ApiException.class, () -> ledgerService.createJournal(
                new CreateJournalRequest(
                        JournalType.PAYMENT,
                        "payment-conflict-001",
                        List.of(
                                new CreateLedgerLegRequest(payer.getId(), LedgerDirection.DEBIT, new BigDecimal("39.00"), "USD"),
                                new CreateLedgerLegRequest(payee.getId(), LedgerDirection.CREDIT, new BigDecimal("39.00"), "USD")
                        )
                )
        ));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsJournalWhenAccountDoesNotSupportCurrency() {
        AccountEntity usdOnlyAccount = createAccount("payer-5", "USD");
        AccountEntity eurAccount = createAccount("payee-5", "EUR");

        ApiException exception = assertThrows(ApiException.class, () -> ledgerService.createJournal(
                new CreateJournalRequest(
                        JournalType.PAYMENT,
                        "payment-unsupported-currency",
                        List.of(
                                new CreateLedgerLegRequest(usdOnlyAccount.getId(), LedgerDirection.DEBIT, new BigDecimal("10.00"), "EUR"),
                                new CreateLedgerLegRequest(eurAccount.getId(), LedgerDirection.CREDIT, new BigDecimal("10.00"), "EUR")
                        )
                )
        ));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception).hasMessageContaining("does not support currency EUR");
    }

    @Test
    void frozenAccounts_onlyAllowControlledInterventionJournals() {
        AccountEntity frozenAccount = createAccount("payer-6", "USD");
        AccountEntity counterparty = createAccount("payee-6", "USD");
        accountService.updateStatus(frozenAccount.getId(), "FROZEN", "ops freeze", "ledger-freeze-1");

        ApiException paymentException = assertThrows(ApiException.class, () -> ledgerService.createJournal(
                new CreateJournalRequest(
                        JournalType.PAYMENT,
                        "payment-frozen-account",
                        List.of(
                                new CreateLedgerLegRequest(frozenAccount.getId(), LedgerDirection.DEBIT, new BigDecimal("10.00"), "USD"),
                                new CreateLedgerLegRequest(counterparty.getId(), LedgerDirection.CREDIT, new BigDecimal("10.00"), "USD")
                        )
                )
        ));

        assertThat(paymentException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(paymentException).hasMessageContaining("cannot be posted to by PAYMENT journals");

        JournalResponse reversal = ledgerService.createJournal(
                new CreateJournalRequest(
                        JournalType.REVERSAL,
                        "reversal-frozen-account",
                        List.of(
                                new CreateLedgerLegRequest(frozenAccount.getId(), LedgerDirection.DEBIT, new BigDecimal("10.00"), "USD"),
                                new CreateLedgerLegRequest(counterparty.getId(), LedgerDirection.CREDIT, new BigDecimal("10.00"), "USD")
                        )
                )
        );

        assertThat(reversal.entries()).hasSize(2);
    }

    private AccountEntity createAccount(String ownerId, String currency) {
        return accountService.create(new CreateAccountRequest(ownerId, currency, null));
    }
}
