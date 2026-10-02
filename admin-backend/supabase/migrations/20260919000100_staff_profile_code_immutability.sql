create or replace function public.prevent_staff_code_change()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.staff_code is distinct from old.staff_code then
    raise exception using
      errcode = '23514',
      constraint = 'ck_staff_profile_code_immutable',
      message = 'staff code cannot be changed after issuance';
  end if;
  return new;
end;
$$;

create trigger tr_staff_profile_code_immutable
  before update of staff_code on public.staff_profile
  for each row execute function public.prevent_staff_code_change();
