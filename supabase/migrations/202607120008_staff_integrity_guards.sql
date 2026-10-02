create or replace function public.validate_staff_profile_admin_link()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.admin_user_id is not null and not exists (
    select 1 from public.admin_user
    where id = new.admin_user_id and status = 'ACTIVE'
  ) then
    raise exception using
      errcode = '23514',
      constraint = 'ck_staff_profile_active_admin_user',
      message = 'staff profile can link only an active admin user';
  end if;
  return new;
end;
$$;
create trigger tr_staff_profile_active_admin_user
  before insert or update of admin_user_id on public.staff_profile
  for each row execute function public.validate_staff_profile_admin_link();
create or replace function public.validate_staff_profile_assignment_period()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if exists (
    select 1
    from public.class_staff_assignment assignment
    where assignment.staff_profile_id = new.id
      and (
        assignment.effective_from < new.hired_on
        or (
          new.left_on is not null
          and coalesce(assignment.effective_to, 'infinity'::date) > new.left_on
        )
      )
  ) then
    raise exception using
      errcode = '23514',
      constraint = 'ck_staff_profile_assignment_period',
      message = 'employment period must contain every staff assignment';
  end if;
  return new;
end;
$$;
create trigger tr_staff_profile_assignment_period
  before update of hired_on, left_on, status on public.staff_profile
  for each row execute function public.validate_staff_profile_assignment_period();
