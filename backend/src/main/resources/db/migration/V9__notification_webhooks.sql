create table notification_endpoints (
    id uuid primary key,
    name varchar(120) not null,
    url varchar(1024) not null,
    subscribed_event_types varchar(512) not null,
    signing_secret varchar(256) not null,
    active boolean not null default true,
    max_attempts integer not null default 3,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table notification_deliveries (
    id uuid primary key,
    endpoint_id uuid not null references notification_endpoints(id),
    payment_id uuid references payment_intents(id),
    event_type varchar(96) not null,
    correlation_id varchar(128),
    payload_json text not null,
    payload_hash varchar(64) not null,
    status varchar(24) not null,
    receipt_status varchar(24) not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamp with time zone,
    last_attempt_at timestamp with time zone,
    last_response_status integer,
    last_response_body varchar(2000),
    last_error varchar(512),
    callback_received_at timestamp with time zone,
    callback_reason varchar(512),
    last_signature varchar(256),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create index idx_notification_deliveries_status_next_attempt
    on notification_deliveries(status, next_attempt_at, created_at);

create index idx_notification_deliveries_payment_created
    on notification_deliveries(payment_id, created_at desc);

create table notification_callbacks (
    id uuid primary key,
    endpoint_id uuid not null references notification_endpoints(id),
    delivery_id uuid references notification_deliveries(id),
    payment_id uuid references payment_intents(id),
    callback_id varchar(128) not null,
    fingerprint varchar(64) not null,
    status varchar(24) not null,
    event_type varchar(96),
    payload_json text not null,
    correlation_id varchar(128),
    reason varchar(512),
    created_at timestamp with time zone not null default current_timestamp
);

create unique index idx_notification_callbacks_endpoint_callback
    on notification_callbacks(endpoint_id, callback_id);
