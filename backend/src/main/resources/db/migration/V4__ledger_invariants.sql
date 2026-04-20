create unique index if not exists ux_journal_transactions_type_reference
    on journal_transactions(type, reference_id);
