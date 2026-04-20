create table payment_adjustments (
    id uuid primary key,
    payment_id uuid not null references payment_intents(id),
    type varchar(20) not null,
    amount numeric(19,4) not null,
    fee_amount numeric(19,4) not null,
    currency varchar(3) not null,
    reason varchar(512),
    journal_id uuid not null references journal_transactions(id) unique,
    correlation_id varchar(128),
    idempotency_key varchar(128) not null,
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_payment_adjustments_payment_created on payment_adjustments(payment_id, created_at);
