package com.ledgerforge.payments.ledger;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LedgerDatabaseGuardrailsIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rejectsInvalidJournalRowsBelowServiceLayer() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        insert into journal_transactions (id, type, status, reference_id, created_at)
                        values (?, ?, ?, ?, current_timestamp)
                        """,
                UUID.randomUUID(),
                "SETTLEMENT",
                "COMMITTED",
                "guardrail-invalid-type-" + UUID.randomUUID()
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        insert into journal_transactions (id, type, status, reference_id, created_at)
                        values (?, ?, ?, ?, current_timestamp)
                        """,
                UUID.randomUUID(),
                "PAYMENT",
                "COMMITTED",
                "   "
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsInvalidLedgerEntryRowsBelowServiceLayer() {
        AccountEntity account = createAccount("guardrail-row-" + UUID.randomUUID(), "USD");
        JournalResponse journal = ledgerService.createJournal(new CreateJournalRequest(
                JournalType.PAYMENT,
                "guardrail-valid-journal-" + UUID.randomUUID(),
                List.of(
                        new CreateLedgerLegRequest(account.getId(), LedgerDirection.DEBIT, new BigDecimal("5.00"), "USD"),
                        new CreateLedgerLegRequest(
                                createAccount("guardrail-counterparty-" + UUID.randomUUID(), "USD").getId(),
                                LedgerDirection.CREDIT,
                                new BigDecimal("5.00"),
                                "USD")
                )
        ));

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        insert into ledger_entries (id, journal_id, account_id, direction, amount, currency, created_at, line_number)
                        values (?, ?, ?, ?, ?, ?, current_timestamp, ?)
                        """,
                UUID.randomUUID(),
                journal.id(),
                account.getId(),
                "SIDEWAYS",
                new BigDecimal("1.00"),
                "USD",
                3
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        insert into ledger_entries (id, journal_id, account_id, direction, amount, currency, created_at, line_number)
                        values (?, ?, ?, ?, ?, ?, current_timestamp, ?)
                        """,
                UUID.randomUUID(),
                journal.id(),
                account.getId(),
                "DEBIT",
                new BigDecimal("1.00"),
                "usd",
                4
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void assignsStableLineNumbersAndRejectsDuplicateLineNumbers() {
        JournalResponse journal = createBalancedJournal("guardrail-lines-" + UUID.randomUUID());
        AccountEntity extraAccount = createAccount("guardrail-extra-" + UUID.randomUUID(), "USD");

        assertThat(journal.entries())
                .extracting(LedgerEntryResponse::lineNumber)
                .containsExactly(1, 2);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        insert into ledger_entries (id, journal_id, account_id, direction, amount, currency, created_at, line_number)
                        values (?, ?, ?, ?, ?, ?, current_timestamp, ?)
                        """,
                UUID.randomUUID(),
                journal.id(),
                extraAccount.getId(),
                "DEBIT",
                new BigDecimal("1.00"),
                "USD",
                1
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void journalTransactionsAreAppendOnlyAtDatabaseLevel() {
        JournalResponse journal = createBalancedJournal("guardrail-journal-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update journal_transactions set reference_id = ? where id = ?",
                "mutated-reference",
                journal.id()
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from journal_transactions where id = ?",
                journal.id()
        )).isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select reference_id from journal_transactions where id = ?",
                String.class,
                journal.id()
        )).isEqualTo(journal.referenceId());
    }

    @Test
    void ledgerEntriesAreAppendOnlyAtDatabaseLevel() {
        JournalResponse journal = createBalancedJournal("guardrail-entry-" + UUID.randomUUID());
        UUID entryId = journal.entries().get(0).id();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update ledger_entries set amount = ? where id = ?",
                new BigDecimal("7.00"),
                entryId
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from ledger_entries where id = ?",
                entryId
        )).isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from ledger_entries where id = ?",
                Integer.class,
                entryId
        )).isEqualTo(1);
    }

    private AccountEntity createAccount(String ownerId, String currency) {
        return accountService.create(new CreateAccountRequest(ownerId, currency));
    }

    private JournalResponse createBalancedJournal(String referenceId) {
        AccountEntity payer = createAccount("guardrail-payer-" + UUID.randomUUID(), "USD");
        AccountEntity payee = createAccount("guardrail-payee-" + UUID.randomUUID(), "USD");

        return ledgerService.createJournal(new CreateJournalRequest(
                JournalType.PAYMENT,
                referenceId,
                List.of(
                        new CreateLedgerLegRequest(payer.getId(), LedgerDirection.DEBIT, new BigDecimal("5.00"), "USD"),
                        new CreateLedgerLegRequest(payee.getId(), LedgerDirection.CREDIT, new BigDecimal("5.00"), "USD")
                )
        ));
    }
}
