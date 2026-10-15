create table if not exists rami_art_studio.admin_users (
    id uuid primary key default gen_random_uuid(),
    auth_user_id uuid unique,
    name varchar(100) not null,
    email varchar(255) unique not null,
    role varchar(20) not null default 'admin' check (role in ('admin', 'super_admin')),
    password_hash varchar(255),
    last_login_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

do $auth_rate_limit_schema$
begin
  if to_regclass('rami_art_studio.admin_auth_rate_limit') is null
    and to_regclass('public.admin_auth_rate_limit') is not null then
    alter table public.admin_auth_rate_limit set schema rami_art_studio;
  end if;
end
$auth_rate_limit_schema$;

create table if not exists rami_art_studio.admin_auth_rate_limit (
    bucket_hash char(64) primary key,
    attempts integer not null check (attempts > 0),
    expires_at timestamptz not null,
    constraint ck_admin_auth_rate_limit_hash check (bucket_hash ~ '^[0-9a-f]{64}$')
);

create index if not exists ix_admin_auth_rate_limit_expiry
    on rami_art_studio.admin_auth_rate_limit (expires_at);
alter table rami_art_studio.admin_auth_rate_limit enable row level security;
revoke all on rami_art_studio.admin_auth_rate_limit from anon, authenticated;

create table if not exists rami_art_studio.admin_refresh_tokens (
    id uuid primary key default gen_random_uuid(),
    admin_user_id uuid not null references rami_art_studio.admin_users(id) on delete cascade,
    refresh_token varchar(255) not null unique,
    expires_at timestamptz not null,
    revoked boolean not null default false,
    created_at timestamptz not null default now()
);

create index if not exists idx_admin_refresh_tokens_admin_user_id
    on rami_art_studio.admin_refresh_tokens(admin_user_id);
create index if not exists idx_admin_refresh_tokens_expires_at
    on rami_art_studio.admin_refresh_tokens(expires_at);

create table if not exists rami_art_studio.classes (
    id uuid primary key default gen_random_uuid(),
    age_group varchar(20) not null check (age_group in ('kindergarten', 'elementary', 'middle')),
    age_range varchar(50) not null,
    title varchar(100) not null,
    description text,
    curriculum text,
    thumbnail_url text,
    sort_order integer not null default 0,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

create table if not exists rami_art_studio.class_tags (
    id uuid primary key default gen_random_uuid(),
    class_id uuid not null references rami_art_studio.classes(id) on delete cascade,
    tag varchar(100) not null,
    sort_order integer not null default 0
);

create table if not exists rami_art_studio.gallery_categories (
    id uuid primary key default gen_random_uuid(),
    name varchar(100) not null,
    slug varchar(100) not null unique,
    sort_order integer not null default 0,
    is_active boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists rami_art_studio.gallery_works (
    id uuid primary key default gen_random_uuid(),
    category_id uuid references rami_art_studio.gallery_categories(id) on delete set null,
    title varchar(255) not null,
    description text,
    image_url text not null,
    image_path text not null,
    age_group varchar(20) check (age_group in ('kindergarten', 'elementary', 'middle')),
    artist_name varchar(100),
    created_year smallint,
    sort_order integer not null default 0,
    is_featured boolean not null default false,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

do $grant_backend$
begin
  if exists (select 1 from pg_roles where rolname = 'rami_backend') then
    execute 'grant usage on schema rami_art_studio to rami_backend';
    execute 'grant select, insert, update, delete on
      rami_art_studio.admin_users,
      rami_art_studio.admin_refresh_tokens,
      rami_art_studio.classes,
      rami_art_studio.class_tags,
      rami_art_studio.gallery_categories,
      rami_art_studio.gallery_works
      to rami_backend';
    execute 'grant select, insert, update, delete on rami_art_studio.admin_auth_rate_limit to rami_backend';
    execute 'drop policy if exists admin_auth_rate_limit_backend_all on rami_art_studio.admin_auth_rate_limit';
    execute 'create policy admin_auth_rate_limit_backend_all on rami_art_studio.admin_auth_rate_limit for all to rami_backend using (true) with check (true)';
  end if;
end
$grant_backend$;
