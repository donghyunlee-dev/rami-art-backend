do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'rami_backend') then
    create role rami_backend nologin nosuperuser nocreatedb nocreaterole noinherit;
  end if;
end;
$$;
grant rami_backend to postgres;
grant usage on schema public to rami_backend;
revoke all on table
  public.admin_role,
  public.admin_permission,
  public.admin_role_permission,
  public.admin_user,
  public.admin_user_role,
  public.admin_session_policy,
  public.admin_session,
  public.audit_log,
  public.idempotency_record
from public, anon, authenticated;
grant select on public.admin_role, public.admin_permission, public.admin_role_permission to rami_backend;
grant select, insert, update on public.admin_user, public.admin_user_role to rami_backend;
grant select, insert, update on public.admin_session_policy, public.admin_session to rami_backend;
grant select, insert on public.audit_log to rami_backend;
grant select, insert, update, delete on public.idempotency_record to rami_backend;
alter table public.admin_role enable row level security;
alter table public.admin_permission enable row level security;
alter table public.admin_role_permission enable row level security;
alter table public.admin_user enable row level security;
alter table public.admin_user_role enable row level security;
alter table public.admin_session_policy enable row level security;
alter table public.admin_session enable row level security;
alter table public.audit_log enable row level security;
alter table public.idempotency_record enable row level security;
create policy admin_role_backend_select on public.admin_role for select to rami_backend using (true);
create policy admin_permission_backend_select on public.admin_permission for select to rami_backend using (true);
create policy admin_role_permission_backend_select on public.admin_role_permission for select to rami_backend using (true);
create policy admin_user_backend_all on public.admin_user for all to rami_backend using (true) with check (true);
create policy admin_user_role_backend_all on public.admin_user_role for all to rami_backend using (true) with check (true);
create policy admin_session_policy_backend_all on public.admin_session_policy for all to rami_backend using (true) with check (true);
create policy admin_session_backend_all on public.admin_session for all to rami_backend using (true) with check (true);
create policy audit_log_backend_select on public.audit_log for select to rami_backend using (true);
create policy audit_log_backend_insert on public.audit_log for insert to rami_backend with check (true);
create policy idempotency_backend_all on public.idempotency_record for all to rami_backend using (true) with check (true);
comment on role rami_backend is 'Minimum-privilege role assumed by the Spring Backend connection.';
