create table broker_consumer_receipts (
    id uuid primary key,
    consumer_name varchar(128) not null,
    event_id uuid not null,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id uuid not null,
    topic_name varchar(128) not null,
    partition_number integer not null,
    record_offset bigint not null,
    processed_at timestamp with time zone not null default current_timestamp
);

create unique index uk_broker_consumer_receipts_consumer_event
    on broker_consumer_receipts(consumer_name, event_id);
