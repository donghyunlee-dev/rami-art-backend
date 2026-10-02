set search_path = public, extensions;
create table public.director_profile (
  id uuid primary key,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  based_on_profile_id uuid references public.director_profile(id) on update restrict on delete restrict,
  name varchar(100) not null default '',
  title varchar(100) not null default '',
  introduction varchar(1000) not null default '',
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_director_profile_revision unique(revision),
  constraint ck_director_profile_revision check (revision >= 1),
  constraint ck_director_profile_status check (status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_director_profile_version check (version >= 0),
  constraint ck_director_profile_lengths check (
    char_length(name) <= 100
    and char_length(title) <= 100
    and char_length(introduction) <= 1000
  ),
  constraint ck_director_profile_publication check (
    (status='DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_director_profile_published_fields check (
    status='DRAFT' or (
      char_length(btrim(name)) between 1 and 100
      and char_length(btrim(title)) between 1 and 100
      and char_length(btrim(introduction)) between 1 and 1000
    )
  )
);
create unique index uq_director_profile_one_draft
  on public.director_profile(status) where status='DRAFT';
create unique index uq_director_profile_one_published
  on public.director_profile(status) where status='PUBLISHED';
create index ix_director_profile_status_updated
  on public.director_profile(status,updated_at desc,id desc);
create index ix_director_profile_status_published
  on public.director_profile(status,published_at desc,id desc);
create trigger tr_director_profile_updated_at
  before update on public.director_profile
  for each row execute function public.set_updated_at();
create table public.director_career (
  id uuid primary key,
  director_profile_id uuid not null references public.director_profile(id) on update restrict on delete cascade,
  period varchar(50),
  title varchar(200) not null,
  display_order integer not null,
  hidden boolean not null default false,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_director_career_profile_id unique(director_profile_id,id),
  constraint uq_director_career_order unique(director_profile_id,display_order),
  constraint ck_director_career_order check(display_order >= 0),
  constraint ck_director_career_title check(char_length(btrim(title)) between 1 and 200),
  constraint ck_director_career_period check(period is null or char_length(btrim(period)) between 1 and 50)
);
create index ix_director_career_profile_order
  on public.director_career(director_profile_id,display_order);
create index ix_director_career_profile_visibility_order
  on public.director_career(director_profile_id,hidden,display_order);
revoke all on table public.director_profile, public.director_career from public,anon,authenticated;
grant select,insert,update on public.director_profile to rami_backend;
grant select,insert,update,delete on public.director_career to rami_backend;
alter table public.director_profile enable row level security;
alter table public.director_career enable row level security;
create policy director_profile_backend_all on public.director_profile
  for all to rami_backend using (true) with check (true);
create policy director_career_backend_all on public.director_career
  for all to rami_backend using (true) with check (true);
comment on table public.director_profile is
  'Immutable published director profile revisions; only a DRAFT row may be edited.';
comment on table public.director_career is
  'Ordered desired-set career items owned by one director profile revision.';
