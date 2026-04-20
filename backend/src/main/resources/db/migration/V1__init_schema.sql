create table accounts (
    id uuid primary key,
    owner_id varchar(128) not null,
    currency varchar(3) not null,
    status varchar(20) not null,
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_accounts_owner_currency on accounts(owner_id, currency);

create table journal_transactions (
    id uuid primary key,
    type varchar(20) not null,
    status varchar(20) not null,
    reference_id varchar(128),
    created_at timestamp with time zone not null default current_timestamp
);

create table ledger_entries (
    id uuid primary key,
    journal_id uuid not null references journal_transactions(id),
    account_id uuid not null references accounts(id),
    direction varchar(10) not null,
    amount numeric(19,4) not null,
    currency varchar(3) not null,
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_ledger_entries_account on ledger_entries(account_id);
create index idx_ledger_entries_journal on ledger_entries(journal_id);

create table payment_intents (
    id uuid primary key,
    payer_account_id uuid not null references accounts(id),
    payee_account_id uuid not null references accounts(id),
    amount numeric(19,4) not null,
    currency varchar(3) not null,
    status varchar(30) not null,
    idempotency_key varchar(128) not null unique,
    risk_score integer,
    risk_decision varchar(10),
    failure_reason varchar(512),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create index idx_payment_intents_payer_created on payment_intents(payer_account_id, created_at);

create table fraud_signals (
    id uuid primary key,
    payment_id uuid not null references payment_intents(id),
    signal_type varchar(64) not null,
    signal_value varchar(256) not null,
    weight integer not null,
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_fraud_signals_payment on fraud_signals(payment_id);

create table review_cases (
    id uuid primary key,
    payment_id uuid not null unique references payment_intents(id),
    reason varchar(512) not null,
    status varchar(20) not null,
    assigned_to varchar(128),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table audit_events (
    id uuid primary key,
    event_type varchar(64) not null,
    payment_id uuid,
    account_id uuid,
    journal_id uuid,
    correlation_id varchar(128),
    details_json varchar(4000),
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_audit_events_created on audit_events(created_at desc);
