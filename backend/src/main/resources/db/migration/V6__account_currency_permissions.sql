create table account_currency_permissions (
    account_id uuid not null references accounts(id),
    currency varchar(3) not null,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (account_id, currency)
);

insert into account_currency_permissions (account_id, currency)
select id, currency
from accounts;
