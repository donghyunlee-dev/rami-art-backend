do $$
declare
    required_table text;
begin
    foreach required_table in array array[
        'admin_role',
        'admin_permission',
        'admin_role_permission',
        'admin_user',
        'admin_user_role',
        'admin_session_policy',
        'admin_session',
        'audit_log'
    ]
    loop
        if to_regclass(format('public.%I', required_table)) is null then
            raise exception 'Required admin foundation table public.% is missing', required_table;
        end if;
    end loop;
end
$$;
