create or replace function public.prevent_student_status_history_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception using
    errcode = '23514',
    constraint = 'ck_student_status_history_immutable',
    message = 'student status history is append only';
end;
$$;

create trigger tr_student_status_history_immutable
before update or delete on public.student_status_history
for each row execute function public.prevent_student_status_history_mutation();
