create table public.admin_password_history (
    id bigint generated always as identity primary key,
    admin_user_id uuid not null references public.admin_user(id) on delete restrict,
    password_hash varchar(255) not null,
    changed_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint ck_admin_password_history_hash check (char_length(password_hash) between 20 and 255)
);

create index ix_admin_password_history_user_changed
    on public.admin_password_history (admin_user_id, changed_at desc, id desc);

create table public.admin_compromised_password (
    password_sha256 char(64) primary key,
    source varchar(50) not null,
    added_at timestamptz not null default now(),
    constraint ck_admin_compromised_password_hash
        check (password_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_admin_compromised_password_source
        check (char_length(btrim(source)) between 1 and 50)
);

insert into public.admin_compromised_password (password_sha256, source)
values
    ('b9c950640e1b3740e98acb93e669c65766f6670dd1609ba91ff41052ba48c6f3', 'bootstrap-common'),
    ('2a33349e7e606a8ad2e30e3c84521f9377450cf09083e162e0a9b1480ce0f972', 'bootstrap-common'),
    ('0de05e45b9df33951c59ccee62ec3f38d61f0daf187f569599a9efd6e9e71fe5', 'bootstrap-common'),
    ('3c8872c094682f4c3fcdffdab80a8d351dbeee9b31a29e6ea092b51cf473e732', 'bootstrap-common');

alter table public.admin_password_history enable row level security;
alter table public.admin_compromised_password enable row level security;
revoke all on public.admin_password_history, public.admin_compromised_password from anon, authenticated;
revoke all on sequence public.admin_password_history_id_seq from anon, authenticated;
