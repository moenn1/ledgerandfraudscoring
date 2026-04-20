create table outbox_messages (
    id uuid primary key,
    destination varchar(128) not null,
    event_type varchar(64) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid,
    correlation_id varchar(128),
    payload_json varchar(8000) not null,
    status varchar(20) not null,
    attempts integer not null default 0,
    max_attempts integer not null,
    next_attempt_at timestamp with time zone not null default current_timestamp,
    last_attempt_at timestamp with time zone,
    published_at timestamp with time zone,
    dead_lettered_at timestamp with time zone,
    last_error varchar(2000),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create index idx_outbox_messages_ready on outbox_messages(status, next_attempt_at, created_at);
create index idx_outbox_messages_created on outbox_messages(created_at desc);
