package com.ledgerforge.payments.ledger.db;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

public class ImmutableLedgerRowsTrigger implements Trigger {

    private String tableName;

    @Override
    public void init(
            Connection connection,
            String schemaName,
            String triggerName,
            String tableName,
            boolean before,
            int type
    ) {
        this.tableName = tableName;
    }

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        String operation = oldRow != null && newRow == null ? "DELETE" : "UPDATE";
        throw new SQLException(
                "Ledger rows are append-only; " + operation + " is not allowed on " + tableName,
                "45000"
        );
    }
}
