alter table audit_events
    add column actor_type varchar(32);

alter table audit_events
    add column actor_id varchar(128);
