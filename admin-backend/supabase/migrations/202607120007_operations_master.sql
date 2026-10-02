create extension if not exists btree_gist with schema extensions;
create extension if not exists pg_trgm with schema extensions;
set search_path = public, extensions;
create table public.course (
  id uuid primary key,
  code varchar(30) not null unique,
  name varchar(100) not null,
  description varchar(500),
  age_guide varchar(100),
  display_order integer not null default 0,
  active boolean not null default true,
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_course_code check (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
  constraint ck_course_name check (char_length(btrim(name)) between 1 and 100),
  constraint ck_course_description check (
    description is null or char_length(btrim(description)) between 1 and 500
  ),
  constraint ck_course_age_guide check (
    age_guide is null or char_length(btrim(age_guide)) between 1 and 100
  ),
  constraint ck_course_display_order check (display_order >= 0),
  constraint ck_course_version check (version >= 0)
);
create unique index uq_course_active_display_order on public.course (display_order) where active;
create index ix_course_active_order_id on public.course (active, display_order, id);
create index ix_course_name_trgm on public.course using gin (name extensions.gin_trgm_ops);
create trigger tr_course_updated_at before update on public.course
  for each row execute function public.set_updated_at();
create table public.class_group (
  id uuid primary key,
  course_id uuid not null references public.course(id) on update restrict on delete restrict,
  code varchar(30) not null unique,
  name varchar(100) not null,
  room_code varchar(30) not null,
  capacity smallint not null,
  waitlist_enabled boolean not null default true,
  makeup_valid_days smallint not null default 0,
  starts_on date not null,
  ends_on date,
  status varchar(20) not null default 'DRAFT',
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_class_group_code check (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
  constraint ck_class_group_name check (char_length(btrim(name)) between 1 and 100),
  constraint ck_class_group_room_code check (
    room_code = upper(btrim(room_code)) and char_length(room_code) between 1 and 30
  ),
  constraint ck_class_group_capacity check (capacity between 1 and 100),
  constraint ck_class_group_makeup_valid_days check (makeup_valid_days between 0 and 180),
  constraint ck_class_group_period check (ends_on is null or ends_on >= starts_on),
  constraint ck_class_group_status check (status in ('DRAFT', 'ACTIVE', 'CLOSED')),
  constraint ck_class_group_version check (version >= 0)
);
create index ix_class_group_course_status_name on public.class_group (course_id, status, name, id);
create index ix_class_group_status_period on public.class_group (status, starts_on, ends_on);
create trigger tr_class_group_updated_at before update on public.class_group
  for each row execute function public.set_updated_at();
create or replace function public.validate_class_group_course()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if not exists (
    select 1 from public.course
    where id = new.course_id and active
  ) then
    raise exception using
      errcode = '23514',
      constraint = 'ck_class_group_active_course',
      message = 'class group requires an active course';
  end if;
  return new;
end;
$$;
create trigger tr_class_group_active_course
  before insert or update of course_id on public.class_group
  for each row execute function public.validate_class_group_course();
create or replace function public.prevent_course_deactivation_with_active_groups()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.active and not new.active and exists (
    select 1 from public.class_group
    where course_id = old.id and status = 'ACTIVE'
  ) then
    raise exception using
      errcode = '23514',
      constraint = 'ck_course_has_no_active_groups',
      message = 'active class groups prevent course deactivation';
  end if;
  return new;
end;
$$;
create trigger tr_course_deactivation_guard
  before update of active on public.course
  for each row execute function public.prevent_course_deactivation_with_active_groups();
create table public.staff_profile (
  id uuid primary key,
  staff_code varchar(30) not null unique,
  name varchar(100) not null,
  display_name varchar(100) not null,
  job_title varchar(20) not null,
  phone_ciphertext bytea not null,
  phone_hash char(64) not null unique,
  phone_last4 char(4) not null,
  hired_on date not null,
  left_on date,
  status varchar(20) not null default 'ACTIVE',
  admin_user_id uuid references public.admin_user(id) on update restrict on delete restrict,
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_staff_profile_code check (staff_code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
  constraint ck_staff_profile_name check (char_length(btrim(name)) between 1 and 100),
  constraint ck_staff_profile_display_name check (char_length(btrim(display_name)) between 1 and 100),
  constraint ck_staff_profile_job_title check (
    job_title in ('DIRECTOR', 'TEACHER', 'ASSISTANT', 'ADMIN')
  ),
  constraint ck_staff_profile_phone_hash check (phone_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_staff_profile_phone_last4 check (phone_last4 ~ '^[0-9]{4}$'),
  constraint ck_staff_profile_period check (left_on is null or left_on >= hired_on),
  constraint ck_staff_profile_status check (status in ('ACTIVE', 'INACTIVE')),
  constraint ck_staff_profile_status_period check (
    (status = 'ACTIVE' and left_on is null) or
    (status = 'INACTIVE' and left_on is not null)
  ),
  constraint ck_staff_profile_version check (version >= 0)
);
create unique index uq_staff_profile_admin_user on public.staff_profile (admin_user_id)
  where admin_user_id is not null;
create index ix_staff_profile_status_name_id on public.staff_profile (status, display_name, id);
create index ix_staff_profile_job_title_status on public.staff_profile (job_title, status);
create trigger tr_staff_profile_updated_at before update on public.staff_profile
  for each row execute function public.set_updated_at();
create table public.class_staff_assignment (
  id uuid primary key,
  class_group_id uuid not null references public.class_group(id) on update restrict on delete restrict,
  staff_profile_id uuid not null references public.staff_profile(id) on update restrict on delete restrict,
  role varchar(20) not null,
  effective_from date not null,
  effective_to date,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_class_staff_assignment_role check (role in ('LEAD', 'ASSISTANT')),
  constraint ck_class_staff_assignment_period check (
    effective_to is null or effective_to >= effective_from
  ),
  constraint ck_class_staff_assignment_version check (version >= 0),
  constraint ex_class_staff_lead_period exclude using gist (
    class_group_id with =,
    daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') with &&
  ) where (role = 'LEAD'),
  constraint ex_class_staff_same_assignment_period exclude using gist (
    class_group_id with =,
    staff_profile_id with =,
    role with =,
    daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') with &&
  )
);
create index ix_class_staff_assignment_class_period
  on public.class_staff_assignment (class_group_id, effective_from, effective_to);
create index ix_class_staff_assignment_staff_period
  on public.class_staff_assignment (staff_profile_id, effective_from, effective_to);
create trigger tr_class_staff_assignment_updated_at before update on public.class_staff_assignment
  for each row execute function public.set_updated_at();
create or replace function public.validate_class_staff_assignment_period()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  staff_from date;
  staff_to date;
  staff_status varchar(20);
  class_from date;
  class_to date;
  class_status varchar(20);
begin
  select hired_on, left_on, status
    into staff_from, staff_to, staff_status
  from public.staff_profile
  where id = new.staff_profile_id
  for key share;

  select starts_on, ends_on, status
    into class_from, class_to, class_status
  from public.class_group
  where id = new.class_group_id
  for key share;

  if staff_status <> 'ACTIVE'
     or new.effective_from < staff_from
     or (staff_to is not null and coalesce(new.effective_to, 'infinity'::date) > staff_to)
     or class_status = 'CLOSED'
     or new.effective_from < class_from
     or (class_to is not null and coalesce(new.effective_to, 'infinity'::date) > class_to) then
    raise exception using
      errcode = '23514',
      constraint = 'ck_class_staff_assignment_owner_period',
      message = 'assignment period must fit active staff employment and class operation';
  end if;
  return new;
end;
$$;
create trigger tr_class_staff_assignment_period_guard
  before insert or update of class_group_id, staff_profile_id, effective_from, effective_to
  on public.class_staff_assignment
  for each row execute function public.validate_class_staff_assignment_period();
reset search_path;
revoke all on table
  public.course,
  public.class_group,
  public.staff_profile,
  public.class_staff_assignment
from public, anon, authenticated;
grant select, insert, update on public.course to rami_backend;
grant select, insert, update, delete on public.class_group to rami_backend;
grant select, insert, update on public.staff_profile to rami_backend;
grant select, insert, update, delete on public.class_staff_assignment to rami_backend;
alter table public.course enable row level security;
alter table public.class_group enable row level security;
alter table public.staff_profile enable row level security;
alter table public.class_staff_assignment enable row level security;
create policy course_backend_all on public.course
  for all to rami_backend using (true) with check (true);
create policy class_group_backend_all on public.class_group
  for all to rami_backend using (true) with check (true);
create policy staff_profile_backend_all on public.staff_profile
  for all to rami_backend using (true) with check (true);
create policy class_staff_assignment_backend_all on public.class_staff_assignment
  for all to rami_backend using (true) with check (true);
