-- Restrict the backend's existing delete grant to unrecorded makeup targets in open sessions.
set search_path = public, extensions;
create or replace function public.validate_makeup_attendance_target_delete()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  session_status varchar(20);
begin
  if old.makeup_case_id is null then
    raise exception using errcode = '42501', message = 'only makeup attendance targets may be deleted';
  end if;
  select status into session_status from public.attendance_session where id = old.attendance_session_id;
  if session_status is distinct from 'OPEN' then
    raise exception using errcode = '55000', message = 'only open makeup attendance targets may be deleted';
  end if;
  if exists (select 1 from public.student_attendance where attendance_session_id = old.attendance_session_id and student_id = old.student_id) then
    raise exception using errcode = '55000', message = 'recorded makeup attendance target may not be deleted';
  end if;
  return old;
end;
$$;
drop trigger if exists tr_makeup_attendance_target_delete on public.attendance_session_student;
create trigger tr_makeup_attendance_target_delete
  before delete on public.attendance_session_student
  for each row execute function public.validate_makeup_attendance_target_delete();
reset search_path;
