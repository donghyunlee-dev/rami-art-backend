create table public.admin_auth_rate_limit (
    bucket_hash char(64) primary key,
    attempts integer not null check (attempts > 0),
    expires_at timestamptz not null,
    constraint ck_admin_auth_rate_limit_hash check (bucket_hash ~ '^[0-9a-f]{64}$')
);
create index ix_admin_auth_rate_limit_expiry on public.admin_auth_rate_limit (expires_at);
alter table public.admin_auth_rate_limit enable row level security;
revoke all on public.admin_auth_rate_limit from anon, authenticated;
