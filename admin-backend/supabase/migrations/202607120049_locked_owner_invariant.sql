create or replace function public.require_active_owner()
returns trigger
language plpgsql
as $$
begin
  if not exists (
    select 1
    from public.admin_user user_account
    join public.admin_user_role user_role
      on user_role.admin_user_id = user_account.id
    join public.admin_role role
      on role.id = user_role.admin_role_id
    where user_account.status in ('ACTIVE', 'LOCKED')
      and role.code = 'OWNER'
  ) then
    raise exception using
      errcode = '23514',
      message = 'at least one active or temporarily locked owner is required';
  end if;
  return null;
end;
$$;
