create or replace function public.reject_audit_log_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception using
    errcode = '23514',
    message = 'audit_log is append-only';
end;
$$;
drop trigger if exists tr_audit_log_append_only on public.audit_log;
create trigger tr_audit_log_append_only
before update or delete on public.audit_log
for each row execute function public.reject_audit_log_mutation();
create or replace function public.guard_admin_session_policy_mutation()
returns trigger
language plpgsql
as $$
begin
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
drop trigger if exists tr_admin_session_policy_immutable on public.admin_session_policy;
create trigger tr_admin_session_policy_immutable
before update or delete on public.admin_session_policy
for each row execute function public.guard_admin_session_policy_mutation();
create or replace function public.reject_admin_user_delete()
returns trigger
language plpgsql
as $$
begin
  raise exception using
    errcode = '23514',
    message = 'admin users cannot be deleted';
end;
$$;
drop trigger if exists tr_admin_user_no_delete on public.admin_user;
create trigger tr_admin_user_no_delete
before delete on public.admin_user
for each row execute function public.reject_admin_user_delete();
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
    where user_account.status = 'ACTIVE'
      and role.code = 'OWNER'
  ) then
    raise exception using
      errcode = '23514',
      message = 'at least one active owner is required';
  end if;
  return null;
end;
$$;
drop trigger if exists tr_admin_user_active_owner on public.admin_user;
create constraint trigger tr_admin_user_active_owner
after insert or update of status on public.admin_user
deferrable initially deferred
for each row execute function public.require_active_owner();
drop trigger if exists tr_admin_user_role_active_owner on public.admin_user_role;
create constraint trigger tr_admin_user_role_active_owner
after insert or update or delete on public.admin_user_role
deferrable initially deferred
for each row execute function public.require_active_owner();
create index if not exists ix_audit_log_filters
  on public.audit_log (task_id, action, result, occurred_at desc, id desc);
