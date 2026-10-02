set search_path = public, extensions;
alter table public.attendance_session_student
  add column makeup_case_id uuid
    references public.makeup_case(id) on update restrict on delete restrict;
create unique index uq_attendance_session_student_makeup_case
  on public.attendance_session_student (makeup_case_id)
  where makeup_case_id is not null;
create index ix_attendance_session_student_makeup_session
  on public.attendance_session_student (attendance_session_id, makeup_case_id)
  where makeup_case_id is not null;
create or replace function public.validate_makeup_attendance_target()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  linked_case public.makeup_case%rowtype;
begin
  if new.makeup_case_id is null then
    return new;
  end if;
  select * into linked_case from public.makeup_case where id = new.makeup_case_id;
  if linked_case.id is null
      or linked_case.student_id <> new.student_id
      or linked_case.reserved_session_id <> new.attendance_session_id
      or linked_case.status not in ('RESERVED', 'COMPLETED') then
    raise exception using errcode = '23514',
      constraint = 'ck_makeup_attendance_target_link',
      message = 'makeup attendance target must match the reserved case';
  end if;
  return new;
end;
$$;
create constraint trigger tr_makeup_attendance_target_link
  after insert or update of attendance_session_id, student_id, makeup_case_id
  on public.attendance_session_student
  deferrable initially deferred
  for each row
  when (new.makeup_case_id is not null)
  execute function public.validate_makeup_attendance_target();
create table public.admin_reauthentication (
  id uuid primary key,
  admin_user_id uuid not null
    references public.admin_user(id) on update restrict on delete restrict,
  admin_session_id uuid not null
    references public.admin_session(id) on update restrict on delete cascade,
  purpose varchar(80) not null,
  token_hash char(64) not null unique,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_admin_reauthentication_purpose check (
    purpose ~ '^[A-Z][A-Z0-9_]{2,79}$'
  ),
  constraint ck_admin_reauthentication_hash check (token_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_admin_reauthentication_expiry check (expires_at > created_at),
  constraint ck_admin_reauthentication_consumed check (
    consumed_at is null or consumed_at >= created_at
  )
);
create index ix_admin_reauthentication_session_active
  on public.admin_reauthentication (admin_session_id, purpose, expires_at)
  where consumed_at is null;
create index ix_admin_reauthentication_expiry
  on public.admin_reauthentication (expires_at)
  where consumed_at is null;
reset search_path;
revoke all on table public.admin_reauthentication from public, anon, authenticated;
grant select, insert, update, delete on public.admin_reauthentication to rami_backend;
alter table public.admin_reauthentication enable row level security;
create policy admin_reauthentication_backend_all on public.admin_reauthentication
  for all to rami_backend using (true) with check (true);
