alter table outbox_events add column attempt_count integer not null default 0;
alter table outbox_events add column next_attempt_at timestamp with time zone not null default current_timestamp;
alter table outbox_events add column last_attempt_at timestamp with time zone;
alter table outbox_events add column dead_lettered_at timestamp with time zone;
alter table outbox_events add column last_error varchar(2000);
alter table outbox_events add column claim_token varchar(36);
alter table outbox_events add column claim_expires_at timestamp with time zone;
alter table outbox_events add column updated_at timestamp with time zone not null default current_timestamp;

update outbox_events
set next_attempt_at = coalesce(next_attempt_at, created_at),
    updated_at = coalesce(updated_at, created_at),
    attempt_count = coalesce(attempt_count, 0);

create index idx_outbox_events_ready
    on outbox_events(published_at, dead_lettered_at, next_attempt_at, created_at);
create index idx_outbox_events_claim
    on outbox_events(claim_expires_at);
