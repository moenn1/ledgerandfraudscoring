create table idempotency_records (
    id uuid primary key,
    scope varchar(128) not null,
    idempotency_key varchar(128) not null,
    request_fingerprint varchar(256) not null,
    response_hash varchar(128) not null,
    created_at timestamp with time zone not null default current_timestamp,
    unique (scope, idempotency_key)
);

create index idx_idempotency_scope_created on idempotency_records(scope, created_at desc);

alter table ledger_entries add constraint chk_ledger_entries_amount_positive check (amount > 0);
