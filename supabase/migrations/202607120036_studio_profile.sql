set search_path = public, extensions;
create table public.studio_profile (
  id uuid primary key,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  based_on_profile_id uuid references public.studio_profile(id) on update restrict on delete restrict,
  studio_name varchar(100) not null,
  phone varchar(16) not null,
  email varchar(254) not null,
  address varchar(200) not null,
  address_detail varchar(100),
  latitude numeric(9,6),
  longitude numeric(9,6),
  business_hours jsonb not null default '[]'::jsonb,
  closed_days varchar(300),
  transit_guide varchar(500),
  parking_guide varchar(500),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_studio_profile_revision unique(revision),
  constraint ck_studio_profile_revision check (revision >= 1),
  constraint ck_studio_profile_status check (status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_studio_profile_version check (version >= 0),
  constraint ck_studio_profile_coordinates check (
    (latitude is null and longitude is null)
    or (latitude is not null and longitude is not null
      and latitude between -90 and 90 and longitude between -180 and 180)
  ),
  constraint ck_studio_profile_hours_json check (jsonb_typeof(business_hours) = 'array'),
  constraint ck_studio_profile_optional_text check (
    (address_detail is null or char_length(btrim(address_detail)) between 1 and 100)
    and (closed_days is null or char_length(btrim(closed_days)) between 1 and 300)
    and (transit_guide is null or char_length(btrim(transit_guide)) between 1 and 500)
    and (parking_guide is null or char_length(btrim(parking_guide)) between 1 and 500)
  ),
  constraint ck_studio_profile_publication check (
    (status='DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_studio_profile_published_fields check (
    status='DRAFT' or (
      char_length(btrim(studio_name)) between 1 and 100
      and phone ~ '^\+[1-9][0-9]{7,14}$'
      and email = lower(email)
      and char_length(btrim(email)) between 3 and 254
      and char_length(btrim(address)) between 1 and 200
    )
  )
);
create unique index uq_studio_profile_one_draft
  on public.studio_profile(status) where status='DRAFT';
create unique index uq_studio_profile_one_published
  on public.studio_profile(status) where status='PUBLISHED';
create index ix_studio_profile_status_updated
  on public.studio_profile(status,updated_at desc,id desc);
create index ix_studio_profile_status_published
  on public.studio_profile(status,published_at desc,id desc);
create trigger tr_studio_profile_updated_at
  before update on public.studio_profile
  for each row execute function public.set_updated_at();
revoke all on table public.studio_profile from public,anon,authenticated;
grant select,insert,update on public.studio_profile to rami_backend;
alter table public.studio_profile enable row level security;
create policy studio_profile_backend_all on public.studio_profile
  for all to rami_backend using (true) with check (true);
comment on table public.studio_profile is
  'Immutable published studio fact revisions; only a DRAFT row may be edited.';
