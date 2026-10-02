set search_path = public, extensions;
drop index public.uq_attendance_session_student_makeup_case;
create index ix_attendance_session_student_makeup_case
  on public.attendance_session_student (makeup_case_id, attendance_session_id)
  where makeup_case_id is not null;
reset search_path;
