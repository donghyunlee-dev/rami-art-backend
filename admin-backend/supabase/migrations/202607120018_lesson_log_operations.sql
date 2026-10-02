set search_path = public, extensions;
create table public.lesson_log (
  id uuid primary key,
  attendance_session_id uuid not null references public.attendance_session(id) on update restrict on delete restrict,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  based_on_log_id uuid references public.lesson_log(id) on update restrict on delete restrict,
  lesson_plan_item_id uuid references public.lesson_plan_item(id) on update restrict on delete restrict,
  actual_title varchar(120) not null,
  activities jsonb not null default '[]'::jsonb,
  materials jsonb not null default '[]'::jsonb,
  change_reason varchar(500),
  overall_note_ciphertext bytea,
  amend_reason varchar(200),
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  finalized_by uuid references public.admin_user(id) on update restrict on delete restrict,
  finalized_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_lesson_log_session_revision unique (attendance_session_id, revision),
  constraint ck_lesson_log_revision check (revision >= 1),
  constraint ck_lesson_log_status check (status in ('DRAFT', 'FINALIZED', 'AMENDED')),
  constraint ck_lesson_log_title check (char_length(btrim(actual_title)) between 1 and 120),
  constraint ck_lesson_log_arrays check (
    jsonb_typeof(activities) = 'array' and jsonb_typeof(materials) = 'array'
    and jsonb_array_length(activities) <= 20 and jsonb_array_length(materials) <= 30
  ),
  constraint ck_lesson_log_change_reason check (
    change_reason is null or char_length(btrim(change_reason)) between 1 and 500
  ),
  constraint ck_lesson_log_amend_reason check (
    (revision = 1 and amend_reason is null)
    or (revision > 1 and amend_reason is not null
      and char_length(btrim(amend_reason)) between 5 and 200)
  ),
  constraint ck_lesson_log_finalization check (
    (status = 'DRAFT' and finalized_by is null and finalized_at is null)
    or (status in ('FINALIZED', 'AMENDED') and finalized_by is not null and finalized_at is not null
      and jsonb_array_length(activities) >= 1)
  ),
  constraint ck_lesson_log_version check (version >= 0)
);
create unique index uq_lesson_log_one_draft
  on public.lesson_log (attendance_session_id) where status = 'DRAFT';
create unique index uq_lesson_log_one_finalized
  on public.lesson_log (attendance_session_id) where status = 'FINALIZED';
create index ix_lesson_log_session_status_revision
  on public.lesson_log (attendance_session_id, status, revision desc);
create index ix_lesson_log_finalized_at
  on public.lesson_log (finalized_at desc, id) where finalized_at is not null;
create trigger tr_lesson_log_updated_at before update on public.lesson_log
  for each row execute function public.set_updated_at();
create table public.student_lesson_record (
  id uuid primary key,
  lesson_log_id uuid not null references public.lesson_log(id) on update restrict on delete cascade,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  attendance_status_snapshot varchar(20) not null,
  participation varchar(20),
  progress_note_ciphertext bytea,
  observation_ciphertext bytea,
  absence_note_ciphertext bytea,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_student_lesson_record_log_student unique (lesson_log_id, student_id),
  constraint ck_student_lesson_record_attendance check (
    attendance_status_snapshot in ('PRESENT', 'LATE', 'ABSENT', 'EXCUSED')
  ),
  constraint ck_student_lesson_record_participation check (
    participation is null or participation in ('LOW', 'NORMAL', 'HIGH')
  ),
  constraint ck_student_lesson_record_absence_shape check (
    attendance_status_snapshot not in ('ABSENT', 'EXCUSED')
    or (participation is null and progress_note_ciphertext is null and observation_ciphertext is null)
  )
);
create index ix_student_lesson_record_student_created
  on public.student_lesson_record (student_id, created_at desc, id);
create index ix_student_lesson_record_log_student
  on public.student_lesson_record (lesson_log_id, student_id);
create trigger tr_student_lesson_record_updated_at before update on public.student_lesson_record
  for each row execute function public.set_updated_at();
create or replace function public.validate_student_lesson_record_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  parent_status varchar(20);
  parent_session uuid;
  actual_status varchar(20);
begin
  select status, attendance_session_id into parent_status, parent_session
    from public.lesson_log where id = new.lesson_log_id for key share;
  if parent_status is distinct from 'DRAFT' then
    raise exception using errcode = '55000',
      constraint = 'ck_lesson_log_finalized_immutable',
      message = 'finalized lesson records are immutable';
  end if;
  select attendance.status into actual_status
    from public.attendance_session_student target
    join public.student_attendance attendance
      on attendance.attendance_session_id = target.attendance_session_id
     and attendance.student_id = target.student_id
   where target.attendance_session_id = parent_session and target.student_id = new.student_id;
  if actual_status is null or actual_status <> new.attendance_status_snapshot then
    raise exception using errcode = '23514',
      constraint = 'ck_lesson_log_student_attendance_target',
      message = 'student lesson record must match attendance target and status';
  end if;
  return new;
end;
$$;
create trigger tr_student_lesson_record_validate
  before insert or update on public.student_lesson_record
  for each row execute function public.validate_student_lesson_record_write();
create or replace function public.prevent_student_lesson_record_delete()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if (select status from public.lesson_log where id = old.lesson_log_id) <> 'DRAFT' then
    raise exception using errcode = '55000',
      constraint = 'ck_lesson_log_finalized_immutable',
      message = 'finalized lesson records are immutable';
  end if;
  return old;
end;
$$;
create trigger tr_student_lesson_record_delete_draft_only
  before delete on public.student_lesson_record
  for each row execute function public.prevent_student_lesson_record_delete();
revoke all on table public.lesson_log, public.student_lesson_record
from public, anon, authenticated;
grant select, insert, update on public.lesson_log to rami_backend;
grant select, insert, update, delete on public.student_lesson_record to rami_backend;
alter table public.lesson_log enable row level security;
alter table public.student_lesson_record enable row level security;
create policy lesson_log_backend_all on public.lesson_log
  for all to rami_backend using (true) with check (true);
create policy student_lesson_record_backend_all on public.student_lesson_record
  for all to rami_backend using (true) with check (true);
reset search_path;
