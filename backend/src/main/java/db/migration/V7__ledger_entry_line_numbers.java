package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class V7__ledger_entry_line_numbers extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        addLineNumberColumn(connection);
        backfillLineNumbers(connection);
        addLineNumberConstraints(connection);
    }

    private void addLineNumberColumn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table ledger_entries add column line_number integer");
        }
    }

    private void backfillLineNumbers(Connection connection) throws SQLException {
        Map<UUID, Integer> nextLineNumberByJournal = new HashMap<>();

        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(
                     """
                             select id, journal_id
                             from ledger_entries
                             order by journal_id asc, created_at asc, id asc
                             """
             );
             PreparedStatement update = connection.prepareStatement(
                     "update ledger_entries set line_number = ? where id = ?"
             )) {
            while (rows.next()) {
                UUID journalId = (UUID) rows.getObject("journal_id");
                UUID entryId = (UUID) rows.getObject("id");
                int lineNumber = nextLineNumberByJournal.getOrDefault(journalId, 0) + 1;
                nextLineNumberByJournal.put(journalId, lineNumber);

                update.setInt(1, lineNumber);
                update.setObject(2, entryId);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void addLineNumberConstraints(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table ledger_entries alter column line_number set not null");
            statement.execute(
                    """
                            alter table ledger_entries
                            add constraint chk_ledger_entries_line_number_positive
                            check (line_number > 0)
                            """
            );
            statement.execute(
                    """
                            alter table ledger_entries
                            add constraint ux_ledger_entries_journal_line_number
                            unique (journal_id, line_number)
                            """
            );
        }
    }
}
