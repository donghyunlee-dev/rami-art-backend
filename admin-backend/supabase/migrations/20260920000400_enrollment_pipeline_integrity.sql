create or replace function public.prevent_enrollment_activity_mutation()
returns trigger language plpgsql as $$
begin
  raise exception using errcode = '23514', constraint = 'ck_enrollment_activity_immutable',
    message = 'enrollment activity is append only';
end;
$$;

create trigger tr_enrollment_activity_immutable
before update or delete on public.enrollment_activity
for each row execute function public.prevent_enrollment_activity_mutation();

create or replace function public.validate_enrollment_case_group_course()
returns trigger language plpgsql set search_path = '' as $$
begin
  if new.desired_class_group_id is not null and not exists (
    select 1 from public.class_group g
    where g.id = new.desired_class_group_id
      and (new.desired_course_id is null or g.course_id = new.desired_course_id)
  ) then
    raise exception using errcode = '23514', constraint = 'ck_enrollment_case_group_course',
      message = 'desired class group must belong to desired course';
  end if;
  return new;
end;
$$;

create trigger tr_enrollment_case_group_course
before insert or update of desired_course_id, desired_class_group_id on public.enrollment_case
for each row execute function public.validate_enrollment_case_group_course();
