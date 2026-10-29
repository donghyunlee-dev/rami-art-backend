do $secure_legacy_entities$
declare
  table_name text;
begin
  foreach table_name in array array[
    'admin_users', 'admin_refresh_tokens', 'classes', 'class_tags',
    'gallery_categories', 'gallery_works'
  ] loop
    execute format('alter table rami_art_studio.%I enable row level security', table_name);
    execute format('revoke all on table rami_art_studio.%I from public, anon, authenticated', table_name);
    if exists (select 1 from pg_roles where rolname = 'rami_backend') then
      execute format('drop policy if exists %I on rami_art_studio.%I', table_name || '_backend_all', table_name);
      execute format(
        'create policy %I on rami_art_studio.%I for all to rami_backend using (true) with check (true)',
        table_name || '_backend_all', table_name
      );
    end if;
  end loop;
end
$secure_legacy_entities$;
