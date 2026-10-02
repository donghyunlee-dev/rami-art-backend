set search_path = public, extensions;
create table public.student_attendance (
  id uuid primary key,
  attendance_session_id uuid not null,
  student_id uuid not null,
  status varchar(20) not null,
  check_in_time time(0),
  reason varchar(200),
  makeup_eligible boolean not null default false,
  checked_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  checked_at timestamptz not null default statement_timestamp(),
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_at timestamptz not null default statement_timestamp(),
  version bigint not null default 0,
  constraint uq_student_attendance_session_student unique (attendance_session_id, student_id),
  constraint fk_student_attendance_target foreign key (attendance_session_id, student_id)
    references public.attendance_session_student(attendance_session_id, student_id)
    on update restrict on delete cascade,
  constraint ck_student_attendance_status check (
    status in ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')
  ),
  constraint ck_student_attendance_shape check (
    (status = 'PRESENT' and check_in_time is null and reason is null)
    or (status = 'LATE' and check_in_time is not null and reason is null)
    or (status in ('ABSENT', 'EXCUSED') and check_in_time is null
      and reason is not null and char_length(btrim(reason)) between 1 and 200)
  ),
  constraint ck_student_attendance_makeup check (
    not makeup_eligible or status in ('ABSENT', 'EXCUSED')
  ),
  constraint ck_student_attendance_version check (version >= 0)
);
create index ix_student_attendance_session_status_student
  on public.student_attendance (attendance_session_id, status, student_id);
create index ix_student_attendance_student_updated
  on public.student_attendance (student_id, updated_at desc);
create trigger tr_student_attendance_updated_at before update on public.student_attendance
  for each row execute function public.set_updated_at();
create or replace function public.require_open_attendance_session()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  session_status varchar(20);
begin
  select status into session_status from public.attendance_session
  where id = new.attendance_session_id;
  if session_status is distinct from 'OPEN' then
    raise exception using
      errcode = '55000', constraint = 'ck_student_attendance_open_session',
      message = 'student attendance can only be changed while the session is open';
  end if;
  return new;
end;
$$;
create trigger tr_student_attendance_open_session
  before insert or update on public.student_attendance
  for each row execute function public.require_open_attendance_session();
create table public.makeup_case (
  id uuid primary key,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  origin_attendance_id uuid not null unique
    references public.student_attendance(id) on update restrict on delete restrict,
  origin_session_id uuid not null
    references public.attendance_session(id) on update restrict on delete restrict,
  class_group_id uuid not null
    references public.class_group(id) on update restrict on delete restrict,
  status varchar(20) not null default 'AVAILABLE',
  expires_on date not null,
  reserved_session_id uuid references public.attendance_session(id) on update restrict on delete restrict,
  completed_attendance_id uuid references public.student_attendance(id) on update restrict on delete restrict,
  attempt_count smallint not null default 0,
  waive_reason varchar(200),
  extended_reason varchar(200),
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_makeup_case_status check (
    status in ('AVAILABLE', 'RESERVED', 'COMPLETED', 'EXPIRED', 'WAIVED')
  ),
  constraint ck_makeup_case_attempt check (attempt_count >= 0),
  constraint ck_makeup_case_version check (version >= 0),
  constraint ck_makeup_case_shape check (
    (status = 'AVAILABLE' and reserved_session_id is null
      and completed_attendance_id is null and waive_reason is null)
    or (status = 'RESERVED' and reserved_session_id is not null
      and completed_attendance_id is null and waive_reason is null)
    or (status = 'COMPLETED' and reserved_session_id is not null
      and completed_attendance_id is not null and waive_reason is null)
    or (status = 'EXPIRED' and reserved_session_id is null
      and completed_attendance_id is null and waive_reason is null)
    or (status = 'WAIVED' and completed_attendance_id is null
      and waive_reason is not null and char_length(btrim(waive_reason)) between 1 and 200)
  ),
  constraint ck_makeup_case_extended_reason check (
    extended_reason is null or char_length(btrim(extended_reason)) between 1 and 200
  )
);
create index ix_makeup_case_status_expiry_id
  on public.makeup_case (status, expires_on, id);
create index ix_makeup_case_student_status_created
  on public.makeup_case (student_id, status, created_at desc);
create index ix_makeup_case_reserved_session
  on public.makeup_case (reserved_session_id) where reserved_session_id is not null;
create index ix_makeup_case_origin_session
  on public.makeup_case (origin_session_id, id);
create trigger tr_makeup_case_updated_at before update on public.makeup_case
  for each row execute function public.set_updated_at();
reset search_path;
revoke all on table public.student_attendance, public.makeup_case
from public, anon, authenticated;
grant select, insert, update on public.student_attendance to rami_backend;
grant select, insert, update on public.makeup_case to rami_backend;
alter table public.student_attendance enable row level security;
alter table public.makeup_case enable row level security;
create policy student_attendance_backend_all on public.student_attendance
  for all to rami_backend using (true) with check (true);
create policy makeup_case_backend_all on public.makeup_case
  for all to rami_backend using (true) with check (true);
