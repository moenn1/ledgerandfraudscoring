package db.migration;

import com.ledgerforge.payments.ledger.db.ImmutableLedgerRowsTrigger;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class V6__ledger_database_guardrails extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        addRowValidityConstraints(connection);

        String databaseProductName = connection.getMetaData().getDatabaseProductName();
        if ("PostgreSQL".equalsIgnoreCase(databaseProductName)) {
            addPostgresAppendOnlyTriggers(connection);
            return;
        }
        if ("H2".equalsIgnoreCase(databaseProductName)) {
            addH2AppendOnlyTriggers(connection);
            return;
        }

        throw new FlywayException("Unsupported database for ledger guardrails: " + databaseProductName);
    }

    private void addRowValidityConstraints(Connection connection) throws SQLException {
        executeStatements(
                connection,
                """
                        alter table journal_transactions
                        add constraint chk_journal_transactions_type_valid
                        check (type in ('PAYMENT', 'REFUND', 'REVERSAL', 'RESERVE', 'CAPTURE'))
                        """,
                """
                        alter table journal_transactions
                        add constraint chk_journal_transactions_status_valid
                        check (status in ('COMMITTED', 'REVERSED'))
                        """,
                """
                        alter table journal_transactions
                        add constraint chk_journal_transactions_reference_normalized
                        check (
                            reference_id is null
                            or (
                                char_length(trim(reference_id)) > 0
                                and reference_id = trim(reference_id)
                            )
                        )
                        """,
                """
                        alter table ledger_entries
                        add constraint chk_ledger_entries_direction_valid
                        check (direction in ('DEBIT', 'CREDIT'))
                        """,
                """
                        alter table ledger_entries
                        add constraint chk_ledger_entries_currency_normalized
                        check (
                            char_length(currency) = 3
                            and currency = trim(currency)
                            and currency = upper(currency)
                        )
                        """
        );
    }

    private void addPostgresAppendOnlyTriggers(Connection connection) throws SQLException {
        executeStatements(
                connection,
                """
                        create or replace function prevent_ledger_row_mutation()
                        returns trigger
                        as $$
                        begin
                            raise exception 'Ledger rows are append-only; % is not allowed on %', tg_op, tg_table_name
                                using errcode = '45000';
                        end;
                        $$ language plpgsql
                        """,
                """
                        create trigger trg_journal_transactions_no_update
                        before update on journal_transactions
                        for each row
                        execute function prevent_ledger_row_mutation()
                        """,
                """
                        create trigger trg_journal_transactions_no_delete
                        before delete on journal_transactions
                        for each row
                        execute function prevent_ledger_row_mutation()
                        """,
                """
                        create trigger trg_ledger_entries_no_update
                        before update on ledger_entries
                        for each row
                        execute function prevent_ledger_row_mutation()
                        """,
                """
                        create trigger trg_ledger_entries_no_delete
                        before delete on ledger_entries
                        for each row
                        execute function prevent_ledger_row_mutation()
                        """
        );
    }

    private void addH2AppendOnlyTriggers(Connection connection) throws SQLException {
        String triggerClassName = ImmutableLedgerRowsTrigger.class.getName();
        executeStatements(
                connection,
                """
                        create trigger trg_journal_transactions_no_update
                        before update on journal_transactions
                        for each row
                        call "%s"
                        """.formatted(triggerClassName),
                """
                        create trigger trg_journal_transactions_no_delete
                        before delete on journal_transactions
                        for each row
                        call "%s"
                        """.formatted(triggerClassName),
                """
                        create trigger trg_ledger_entries_no_update
                        before update on ledger_entries
                        for each row
                        call "%s"
                        """.formatted(triggerClassName),
                """
                        create trigger trg_ledger_entries_no_delete
                        before delete on ledger_entries
                        for each row
                        call "%s"
                        """.formatted(triggerClassName)
        );
    }

    private void executeStatements(Connection connection, String... statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
