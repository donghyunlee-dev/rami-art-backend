set search_path = public;
grant delete on public.attendance_session_student to rami_backend;
create policy attendance_session_student_backend_delete
  on public.attendance_session_student
  for delete
  to rami_backend
  using (true);
reset search_path;
