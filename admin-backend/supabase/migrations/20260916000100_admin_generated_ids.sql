alter table public.admin_permission
    alter column id set default gen_random_uuid();

alter table public.admin_session
    alter column id set default gen_random_uuid();

alter table public.audit_log
    alter column id set default gen_random_uuid();
