set search_path = public, extensions;
create table public.tuition_policy (
  id uuid primary key,
  year smallint not null,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  default_due_day smallint not null default 25,
  based_on_policy_id uuid references public.tuition_policy(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_tuition_policy_revision unique (year, revision),
  constraint ck_tuition_policy_year check (year between 2000 and 2100),
  constraint ck_tuition_policy_revision check (revision >= 1),
  constraint ck_tuition_policy_status check (status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_tuition_policy_due_day check (default_due_day between 1 and 31),
  constraint ck_tuition_policy_version check (version >= 0),
  constraint ck_tuition_policy_publication_shape check (
    (status = 'DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_by is not null and published_at is not null)
  )
);
create unique index uq_tuition_policy_draft_year
  on public.tuition_policy (year) where status = 'DRAFT';
create unique index uq_tuition_policy_published_year
  on public.tuition_policy (year) where status = 'PUBLISHED';
create index ix_tuition_policy_year_revision
  on public.tuition_policy (year, revision desc);
create index ix_tuition_policy_status_year
  on public.tuition_policy (status, year);
create trigger tr_tuition_policy_updated_at before update on public.tuition_policy
  for each row execute function public.set_updated_at();
create table public.tuition_policy_item (
  id uuid primary key,
  tuition_policy_id uuid not null
    references public.tuition_policy(id) on update restrict on delete cascade,
  lesson_count_per_week smallint not null,
  monthly_amount numeric(12,0) not null,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_tuition_policy_item_count unique (tuition_policy_id, lesson_count_per_week),
  constraint ck_tuition_policy_item_count check (lesson_count_per_week between 1 and 7),
  constraint ck_tuition_policy_item_amount check (
    monthly_amount between 0 and 999999999999 and scale(monthly_amount) = 0
  )
);
create index ix_tuition_policy_item_policy_count
  on public.tuition_policy_item (tuition_policy_id, lesson_count_per_week);
create index ix_tuition_policy_item_count
  on public.tuition_policy_item (lesson_count_per_week);
create or replace function public.protect_tuition_policy_revision()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    if old.status <> 'DRAFT' then
      raise exception using errcode = '23514', message = 'published tuition policy is immutable';
    end if;
    return old;
  end if;
  if old.status = 'DRAFT' then
    return new;
  end if;
  if old.status = 'PUBLISHED' and new.status = 'ARCHIVED'
      and (new.id, new.year, new.revision, new.default_due_day,
           new.based_on_policy_id, new.published_by, new.published_at,
           new.created_by, new.version, new.created_at)
          is not distinct from
          (old.id, old.year, old.revision, old.default_due_day,
           old.based_on_policy_id, old.published_by, old.published_at,
           old.created_by, old.version, old.created_at) then
    return new;
  end if;
  raise exception using errcode = '23514', message = 'published tuition policy is immutable';
end;
$$;
create trigger tr_tuition_policy_immutable
  before update or delete on public.tuition_policy
  for each row execute function public.protect_tuition_policy_revision();
create or replace function public.require_draft_tuition_policy_item()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  policy_status text;
  policy_id uuid;
begin
  policy_id := case when tg_op = 'DELETE' then old.tuition_policy_id else new.tuition_policy_id end;
  select status into policy_status from public.tuition_policy where id = policy_id;
  if policy_status is distinct from 'DRAFT' then
    raise exception using errcode = '23514', message = 'published tuition policy item is immutable';
  end if;
  return case when tg_op = 'DELETE' then old else new end;
end;
$$;
create trigger tr_tuition_policy_item_immutable
  before insert or update or delete on public.tuition_policy_item
  for each row execute function public.require_draft_tuition_policy_item();
reset search_path;
revoke all on table public.tuition_policy, public.tuition_policy_item
from public, anon, authenticated;
grant select, insert, update, delete on public.tuition_policy to rami_backend;
grant select, insert, update, delete on public.tuition_policy_item to rami_backend;
alter table public.tuition_policy enable row level security;
alter table public.tuition_policy_item enable row level security;
create policy tuition_policy_backend_all on public.tuition_policy
  for all to rami_backend using (true) with check (true);
create policy tuition_policy_item_backend_all on public.tuition_policy_item
  for all to rami_backend using (true) with check (true);
