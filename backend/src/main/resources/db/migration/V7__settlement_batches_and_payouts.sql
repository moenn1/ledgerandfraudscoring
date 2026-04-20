alter table payment_intents
    add column settlement_scheduled_for timestamp with time zone;

alter table payment_intents
    add column settled_at timestamp with time zone;

alter table payment_intents
    add column settlement_batch_id uuid;

update payment_intents
set settled_at = coalesce(settled_at, updated_at)
where status = 'SETTLED';

update payment_intents
set settlement_scheduled_for = coalesce(settlement_scheduled_for, updated_at)
where status = 'CAPTURED';

create table settlement_batches (
    id uuid primary key,
    cutoff_at timestamp with time zone not null,
    currency varchar(3) not null,
    status varchar(20) not null,
    payment_count integer not null,
    gross_amount numeric(19,4) not null,
    fee_amount numeric(19,4) not null,
    net_amount numeric(19,4) not null,
    created_at timestamp with time zone not null default current_timestamp,
    completed_at timestamp with time zone
);

create unique index idx_settlement_batches_cutoff_currency
    on settlement_batches(cutoff_at, currency);

create table payouts (
    id uuid primary key,
    settlement_batch_id uuid not null references settlement_batches(id),
    payee_account_id uuid not null references accounts(id),
    currency varchar(3) not null,
    status varchar(20) not null,
    gross_amount numeric(19,4) not null,
    fee_amount numeric(19,4) not null,
    net_amount numeric(19,4) not null,
    scheduled_for timestamp with time zone not null,
    executed_at timestamp with time zone,
    journal_id uuid references journal_transactions(id) unique,
    delay_reason varchar(512),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create unique index idx_payouts_batch_payee
    on payouts(settlement_batch_id, payee_account_id);

create index idx_payouts_status_schedule
    on payouts(status, scheduled_for);

insert into accounts (id, owner_id, currency, status)
values
  ('00000000-0000-0000-0000-000000000031', 'SYSTEM_PAYOUT_CLEARING', 'USD', 'ACTIVE'),
  ('00000000-0000-0000-0000-000000000032', 'SYSTEM_PAYOUT_CLEARING', 'EUR', 'ACTIVE');

insert into account_currency_permissions (account_id, currency)
values
  ('00000000-0000-0000-0000-000000000031', 'USD'),
  ('00000000-0000-0000-0000-000000000032', 'EUR');
