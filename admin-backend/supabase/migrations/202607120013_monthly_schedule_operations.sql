create extension if not exists btree_gist with schema extensions;
set search_path = public, extensions;
create table public.monthly_schedule (
  id uuid primary key,
  year_month char(7) not null,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  based_on_schedule_id uuid references public.monthly_schedule(id) on update restrict on delete restrict,
  change_summary varchar(500),
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_monthly_schedule_id_year_month unique (id, year_month),
  constraint uq_monthly_schedule_month_revision unique (year_month, revision),
  constraint ck_monthly_schedule_year_month check (
    year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
  ),
  constraint ck_monthly_schedule_revision check (revision >= 1),
  constraint ck_monthly_schedule_status check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  constraint ck_monthly_schedule_publication check (
    (status = 'DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED', 'ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_monthly_schedule_summary check (
    change_summary is null or char_length(btrim(change_summary)) between 1 and 500
  ),
  constraint ck_monthly_schedule_version check (version >= 0)
);
create unique index uq_monthly_schedule_one_draft
  on public.monthly_schedule (year_month) where status = 'DRAFT';
create unique index uq_monthly_schedule_one_published
  on public.monthly_schedule (year_month) where status = 'PUBLISHED';
create index ix_monthly_schedule_month_revision
  on public.monthly_schedule (year_month, revision desc);
create index ix_monthly_schedule_status_month
  on public.monthly_schedule (status, year_month);
create trigger tr_monthly_schedule_updated_at before update on public.monthly_schedule
  for each row execute function public.set_updated_at();
create table public.monthly_schedule_item (
  id uuid primary key,
  schedule_slot_id uuid not null references public.schedule_slot(id) on update restrict on delete restrict,
  monthly_schedule_id uuid not null references public.monthly_schedule(id) on update restrict on delete cascade,
  day_of_week smallint not null,
  start_time time(0) not null,
  end_time time(0) not null,
  title varchar(100) not null,
  room_code varchar(30) not null,
  display_order integer not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_monthly_schedule_item_parent unique (id, monthly_schedule_id),
  constraint uq_monthly_schedule_item_slot unique (monthly_schedule_id, schedule_slot_id),
  constraint uq_monthly_schedule_item_order unique (monthly_schedule_id, day_of_week, display_order),
  constraint ck_monthly_schedule_item_day check (day_of_week between 1 and 7),
  constraint ck_monthly_schedule_item_time check (
    end_time > start_time
    and extract(second from start_time) = 0 and extract(second from end_time) = 0
    and mod(extract(minute from start_time)::integer, 5) = 0
    and mod(extract(minute from end_time)::integer, 5) = 0
  ),
  constraint ck_monthly_schedule_item_title check (char_length(btrim(title)) between 1 and 100),
  constraint ck_monthly_schedule_item_room check (
    room_code = upper(btrim(room_code)) and char_length(room_code) between 1 and 30
  ),
  constraint ck_monthly_schedule_item_order check (display_order >= 0)
);
create index ix_monthly_schedule_item_timeline
  on public.monthly_schedule_item (monthly_schedule_id, day_of_week, start_time, id);
create index ix_monthly_schedule_item_slot
  on public.monthly_schedule_item (schedule_slot_id, monthly_schedule_id);
create table public.schedule_override (
  id uuid primary key,
  monthly_schedule_id uuid not null references public.monthly_schedule(id) on update restrict on delete cascade,
  schedule_item_id uuid,
  class_group_id uuid references public.class_group(id) on update restrict on delete restrict,
  target_date date not null,
  type varchar(20) not null,
  start_time time(0),
  end_time time(0),
  title varchar(100),
  room_code varchar(30),
  reason varchar(200) not null,
  created_at timestamptz not null default statement_timestamp(),
  constraint fk_schedule_override_parent_item foreign key (schedule_item_id, monthly_schedule_id)
    references public.monthly_schedule_item(id, monthly_schedule_id) on update restrict on delete cascade,
  constraint uq_schedule_override_item_date_type unique (
    monthly_schedule_id, schedule_item_id, target_date, type
  ),
  constraint ck_schedule_override_type check (type in ('CANCEL', 'TIME_CHANGE', 'MAKEUP')),
  constraint ck_schedule_override_reason check (char_length(btrim(reason)) between 1 and 200),
  constraint ck_schedule_override_time check (
    (start_time is null and end_time is null)
    or (start_time is not null and end_time is not null and end_time > start_time
      and extract(second from start_time) = 0 and extract(second from end_time) = 0
      and mod(extract(minute from start_time)::integer, 5) = 0
      and mod(extract(minute from end_time)::integer, 5) = 0)
  ),
  constraint ck_schedule_override_room check (
    room_code is null or (
      room_code = upper(btrim(room_code)) and char_length(room_code) between 1 and 30
    )
  ),
  constraint ck_schedule_override_title check (
    title is null or char_length(btrim(title)) between 1 and 100
  ),
  constraint ck_schedule_override_shape check (
    (type = 'CANCEL' and schedule_item_id is not null and class_group_id is null
      and start_time is null and end_time is null and title is null and room_code is null)
    or (type = 'TIME_CHANGE' and schedule_item_id is not null and class_group_id is null
      and start_time is not null and end_time is not null and title is null and room_code is not null)
    or (type = 'MAKEUP' and start_time is not null and end_time is not null and room_code is not null
      and ((schedule_item_id is not null and class_group_id is null and title is null)
        or (schedule_item_id is null and class_group_id is not null and title is not null)))
  )
);
create index ix_schedule_override_date
  on public.schedule_override (monthly_schedule_id, target_date, start_time, id);
create index ix_schedule_override_item
  on public.schedule_override (schedule_item_id, target_date, type);
create index ix_schedule_override_group_date
  on public.schedule_override (class_group_id, target_date) where class_group_id is not null;
create or replace function public.validate_schedule_child_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  parent_id uuid;
  parent_status varchar(20);
begin
  parent_id := case when tg_op = 'DELETE' then old.monthly_schedule_id else new.monthly_schedule_id end;
  select status into parent_status from public.monthly_schedule where id = parent_id;
  if parent_status is distinct from 'DRAFT' then
    raise exception using
      errcode = '55000',
      constraint = 'ck_published_schedule_immutable',
      message = 'published or archived schedule children are immutable';
  end if;
  return case when tg_op = 'DELETE' then old else new end;
end;
$$;
create trigger tr_monthly_schedule_item_draft_only
  before insert or update or delete on public.monthly_schedule_item
  for each row execute function public.validate_schedule_child_write();
create trigger tr_schedule_override_draft_only
  before insert or update or delete on public.schedule_override
  for each row execute function public.validate_schedule_child_write();
create or replace function public.validate_schedule_override_context()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  parent_month char(7);
  item_day smallint;
  group_status varchar(20);
begin
  select year_month into parent_month from public.monthly_schedule where id = new.monthly_schedule_id;
  if to_char(new.target_date, 'YYYY-MM') <> parent_month then
    raise exception using
      errcode = '23514', constraint = 'ck_schedule_override_target_month',
      message = 'override target date must belong to its schedule month';
  end if;
  if new.schedule_item_id is not null and new.type in ('CANCEL', 'TIME_CHANGE') then
    select day_of_week into item_day from public.monthly_schedule_item
      where id = new.schedule_item_id and monthly_schedule_id = new.monthly_schedule_id;
    if item_day is null or extract(isodow from new.target_date)::smallint <> item_day then
      raise exception using
        errcode = '23514', constraint = 'ck_schedule_override_item_day',
        message = 'cancel and time change must target the item weekday';
    end if;
  end if;
  if new.class_group_id is not null then
    select status into group_status from public.class_group where id = new.class_group_id;
    if group_status is distinct from 'ACTIVE' then
      raise exception using
        errcode = '23514', constraint = 'ck_schedule_override_active_group',
        message = 'independent makeup requires an active class group';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_schedule_override_context
  before insert or update on public.schedule_override
  for each row execute function public.validate_schedule_override_context();
create table public.lesson_plan (
  id uuid primary key,
  class_group_id uuid not null references public.class_group(id) on update restrict on delete restrict,
  year_month char(7) not null,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  based_on_plan_id uuid references public.lesson_plan(id) on update restrict on delete restrict,
  change_summary varchar(500),
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_lesson_plan_group_month_revision unique (class_group_id, year_month, revision),
  constraint ck_lesson_plan_month check (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  constraint ck_lesson_plan_revision check (revision >= 1),
  constraint ck_lesson_plan_status check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  constraint ck_lesson_plan_publication check (
    (status = 'DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED', 'ARCHIVED') and published_by is not null
      and published_at is not null and change_summary is not null)
  ),
  constraint ck_lesson_plan_version check (version >= 0)
);
create unique index uq_lesson_plan_one_draft
  on public.lesson_plan (class_group_id, year_month) where status = 'DRAFT';
create unique index uq_lesson_plan_one_published
  on public.lesson_plan (class_group_id, year_month) where status = 'PUBLISHED';
create index ix_lesson_plan_month_status_group
  on public.lesson_plan (year_month, status, class_group_id);
create trigger tr_lesson_plan_updated_at before update on public.lesson_plan
  for each row execute function public.set_updated_at();
create table public.lesson_plan_item (
  id uuid primary key,
  lesson_plan_id uuid not null references public.lesson_plan(id) on update restrict on delete cascade,
  planned_date date not null,
  sequence smallint not null default 1,
  title varchar(120) not null,
  objectives jsonb not null default '[]'::jsonb,
  activities jsonb not null default '[]'::jsonb,
  materials jsonb not null default '[]'::jsonb,
  preparations jsonb not null default '[]'::jsonb,
  internal_note varchar(1000),
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_lesson_plan_item_sequence unique (lesson_plan_id, planned_date, sequence),
  constraint ck_lesson_plan_item_sequence check (sequence between 1 and 20),
  constraint ck_lesson_plan_item_title check (char_length(btrim(title)) between 1 and 120),
  constraint ck_lesson_plan_item_arrays check (
    jsonb_typeof(objectives) = 'array' and jsonb_typeof(activities) = 'array'
    and jsonb_typeof(materials) = 'array' and jsonb_typeof(preparations) = 'array'
  ),
  constraint ck_lesson_plan_item_note check (
    internal_note is null or char_length(btrim(internal_note)) between 1 and 1000
  )
);
create index ix_lesson_plan_item_date on public.lesson_plan_item (planned_date, lesson_plan_id);
create table public.attendance_session (
  id uuid primary key,
  schedule_item_id uuid references public.monthly_schedule_item(id) on update restrict on delete restrict,
  schedule_override_id uuid references public.schedule_override(id) on update restrict on delete restrict,
  schedule_slot_id uuid references public.schedule_slot(id) on update restrict on delete restrict,
  class_group_id uuid not null references public.class_group(id) on update restrict on delete restrict,
  lesson_plan_item_id uuid references public.lesson_plan_item(id) on update restrict on delete restrict,
  attendance_date date not null,
  class_name_snapshot varchar(120) not null,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  status varchar(20) not null default 'OPEN',
  cancel_reason varchar(200),
  target_count integer not null default 0,
  present_count integer,
  late_count integer,
  absent_count integer,
  excused_count integer,
  closed_by uuid references public.admin_user(id) on update restrict on delete restrict,
  closed_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_attendance_session_source check (
    schedule_item_id is not null or schedule_override_id is not null
  ),
  constraint ck_attendance_session_time check (ends_at > starts_at),
  constraint ck_attendance_session_name check (char_length(btrim(class_name_snapshot)) between 1 and 120),
  constraint ck_attendance_session_status check (status in ('OPEN', 'CLOSED', 'CANCELLED')),
  constraint ck_attendance_session_target check (target_count >= 0),
  constraint ck_attendance_session_counts check (
    (present_count is null or present_count >= 0)
    and (late_count is null or late_count >= 0)
    and (absent_count is null or absent_count >= 0)
    and (excused_count is null or excused_count >= 0)
  ),
  constraint ck_attendance_session_closed check (
    (status = 'CLOSED' and target_count > 0 and closed_by is not null and closed_at is not null
      and present_count is not null and late_count is not null
      and absent_count is not null and excused_count is not null
      and present_count + late_count + absent_count + excused_count = target_count)
    or (status <> 'CLOSED' and closed_by is null and closed_at is null
      and present_count is null and late_count is null
      and absent_count is null and excused_count is null)
  ),
  constraint ck_attendance_session_cancelled check (
    (status = 'CANCELLED' and cancel_reason is not null)
    or (status <> 'CANCELLED' and cancel_reason is null)
  ),
  constraint ck_attendance_session_version check (version >= 0)
);
create unique index uq_attendance_regular_occurrence
  on public.attendance_session (schedule_item_id, attendance_date)
  where schedule_item_id is not null and schedule_override_id is null;
create unique index uq_attendance_override_occurrence
  on public.attendance_session (schedule_override_id)
  where schedule_override_id is not null;
create unique index uq_attendance_lesson_plan_item
  on public.attendance_session (lesson_plan_item_id) where lesson_plan_item_id is not null;
create index ix_attendance_session_slot_date
  on public.attendance_session (schedule_slot_id, attendance_date);
create index ix_attendance_session_group_date_status
  on public.attendance_session (class_group_id, attendance_date, status);
create index ix_attendance_session_date_start
  on public.attendance_session (attendance_date, starts_at, id);
create index ix_attendance_session_status_date
  on public.attendance_session (status, attendance_date);
create trigger tr_attendance_session_updated_at before update on public.attendance_session
  for each row execute function public.set_updated_at();
create table public.attendance_session_student (
  attendance_session_id uuid not null references public.attendance_session(id) on update restrict on delete cascade,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  student_name_snapshot varchar(100) not null,
  display_order integer not null,
  assignment_id uuid references public.student_schedule_assignment(id) on update restrict on delete set null,
  created_at timestamptz not null default statement_timestamp(),
  primary key (attendance_session_id, student_id),
  constraint uq_attendance_session_student_order unique (attendance_session_id, display_order),
  constraint ck_attendance_session_student_name check (
    char_length(btrim(student_name_snapshot)) between 1 and 100
  ),
  constraint ck_attendance_session_student_order check (display_order >= 0)
);
create index ix_attendance_session_student_student
  on public.attendance_session_student (student_id, attendance_session_id);
create index ix_attendance_session_student_assignment
  on public.attendance_session_student (assignment_id) where assignment_id is not null;
reset search_path;
revoke all on table
  public.monthly_schedule,
  public.monthly_schedule_item,
  public.schedule_override,
  public.lesson_plan,
  public.lesson_plan_item,
  public.attendance_session,
  public.attendance_session_student
from public, anon, authenticated;
grant select, insert, update on public.monthly_schedule to rami_backend;
grant select, insert, update, delete on public.monthly_schedule_item to rami_backend;
grant select, insert, update, delete on public.schedule_override to rami_backend;
grant select, insert, update on public.lesson_plan to rami_backend;
grant select, insert, update, delete on public.lesson_plan_item to rami_backend;
grant select, insert, update on public.attendance_session to rami_backend;
grant select, insert on public.attendance_session_student to rami_backend;
alter table public.monthly_schedule enable row level security;
alter table public.monthly_schedule_item enable row level security;
alter table public.schedule_override enable row level security;
alter table public.lesson_plan enable row level security;
alter table public.lesson_plan_item enable row level security;
alter table public.attendance_session enable row level security;
alter table public.attendance_session_student enable row level security;
create policy monthly_schedule_backend_all on public.monthly_schedule
  for all to rami_backend using (true) with check (true);
create policy monthly_schedule_item_backend_all on public.monthly_schedule_item
  for all to rami_backend using (true) with check (true);
create policy schedule_override_backend_all on public.schedule_override
  for all to rami_backend using (true) with check (true);
create policy lesson_plan_backend_all on public.lesson_plan
  for all to rami_backend using (true) with check (true);
create policy lesson_plan_item_backend_all on public.lesson_plan_item
  for all to rami_backend using (true) with check (true);
create policy attendance_session_backend_all on public.attendance_session
  for all to rami_backend using (true) with check (true);
create policy attendance_session_student_backend_select on public.attendance_session_student
  for select to rami_backend using (true);
create policy attendance_session_student_backend_insert on public.attendance_session_student
  for insert to rami_backend with check (true);
