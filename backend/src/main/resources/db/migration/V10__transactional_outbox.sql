create table outbox_events (
    id uuid primary key,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    event_type varchar(128) not null,
    event_version integer not null default 1,
    partition_key varchar(128) not null,
    correlation_id varchar(128),
    idempotency_key varchar(128),
    payload_json varchar(4000) not null,
    attempt_count integer not null default 0,
    available_at timestamp with time zone not null default current_timestamp,
    leased_until timestamp with time zone,
    lease_owner varchar(128),
    published_at timestamp with time zone,
    last_error varchar(512),
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_outbox_events_delivery on outbox_events(published_at, available_at, created_at);
create index idx_outbox_events_aggregate on outbox_events(aggregate_type, aggregate_id, created_at);
