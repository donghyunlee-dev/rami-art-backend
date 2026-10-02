set search_path = public, extensions;
create or replace function public.protect_student_schedule_assignment_history()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' and exists (
    select 1 from public.attendance_session_student
    where assignment_id = old.id
  ) then
    raise exception using
      errcode = '23514', constraint = 'ck_assignment_history_delete',
      message = 'an assignment with attendance snapshots cannot be deleted';
  end if;

  if tg_op = 'UPDATE' and exists (
    select 1
    from public.attendance_session_student target
    join public.attendance_session session
      on session.id = target.attendance_session_id
    where target.assignment_id = old.id
      and (session.attendance_date < new.effective_from
        or (new.effective_to is not null and session.attendance_date > new.effective_to))
  ) then
    raise exception using
      errcode = '23514', constraint = 'ck_assignment_history_period',
      message = 'an assignment period cannot exclude attendance snapshots';
  end if;

  return case when tg_op = 'DELETE' then old else new end;
end;
$$;
create trigger tr_student_schedule_assignment_history
  before update or delete on public.student_schedule_assignment
  for each row execute function public.protect_student_schedule_assignment_history();
create or replace function public.validate_attendance_assignment_snapshot()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  assignment_student uuid;
  assignment_slot uuid;
  assignment_from date;
  assignment_to date;
  session_slot uuid;
  session_date date;
begin
  if new.assignment_id is null then
    return new;
  end if;

  select student_id, schedule_slot_id, effective_from, effective_to
    into assignment_student, assignment_slot, assignment_from, assignment_to
  from public.student_schedule_assignment where id = new.assignment_id;
  select schedule_slot_id, attendance_date into session_slot, session_date
  from public.attendance_session where id = new.attendance_session_id;

  if assignment_student is distinct from new.student_id
      or assignment_slot is distinct from session_slot
      or session_date < assignment_from
      or (assignment_to is not null and session_date > assignment_to) then
    raise exception using
      errcode = '23514', constraint = 'ck_attendance_assignment_snapshot',
      message = 'attendance target must match assignment student, slot, and period';
  end if;
  return new;
end;
$$;
create trigger tr_attendance_assignment_snapshot
  before insert or update on public.attendance_session_student
  for each row execute function public.validate_attendance_assignment_snapshot();
reset search_path;
