create or replace function public.security_maintenance_allowed()
returns boolean
language sql
stable
set search_path = pg_catalog
as $$
  select current_user = 'postgres'
     and coalesce(
       current_setting('rami.allow_security_maintenance', true),
       'off'
     ) = 'on';
$$;
revoke all on function public.security_maintenance_allowed() from public;
create or replace function public.reject_audit_log_mutation()
returns trigger
language plpgsql
as $$
begin
  if public.security_maintenance_allowed() then
    return case when tg_op = 'DELETE' then old else new end;
  end if;

  raise exception using
    errcode = '23514',
    message = 'audit_log is append-only';
end;
$$;
create or replace function public.guard_admin_session_policy_mutation()
returns trigger
language plpgsql
as $$
begin
  if public.security_maintenance_allowed() then
    return case when tg_op = 'DELETE' then old else new end;
  end if;

  if tg_op = 'DELETE' then
    raise exception using
      errcode = '23514',
      message = 'admin_session_policy versions cannot be deleted';
  end if;

  if old.effective_to is not null
     or new.effective_to is null
     or new.effective_to <= old.effective_from
     or row(
       new.id,
       new.version,
       new.max_failed_attempts,
       new.lock_duration_minutes,
       new.idle_timeout_minutes,
       new.absolute_timeout_minutes,
       new.expiry_warning_minutes,
       new.change_reason,
       new.effective_from,
       new.created_by,
       new.created_at
     ) is distinct from row(
       old.id,
       old.version,
       old.max_failed_attempts,
       old.lock_duration_minutes,
       old.idle_timeout_minutes,
       old.absolute_timeout_minutes,
       old.expiry_warning_minutes,
       old.change_reason,
       old.effective_from,
       old.created_by,
       old.created_at
     ) then
    raise exception using
      errcode = '23514',
      message = 'admin_session_policy versions are immutable';
  end if;
  return new;
end;
$$;
create or replace function public.reject_admin_user_delete()
returns trigger
language plpgsql
as $$
begin
  if public.security_maintenance_allowed() then
    return old;
  end if;

  raise exception using
    errcode = '23514',
    message = 'admin users cannot be deleted';
end;
$$;
