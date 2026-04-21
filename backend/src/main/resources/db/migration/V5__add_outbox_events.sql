create table outbox_events (
    id uuid primary key,
    event_type varchar(64) not null,
    payment_id uuid,
    journal_id uuid,
    correlation_id varchar(128),
    payload_json varchar(4000),
    published_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp
);

create index idx_outbox_events_created on outbox_events(created_at desc);
create index idx_outbox_events_payment on outbox_events(payment_id, created_at desc);
