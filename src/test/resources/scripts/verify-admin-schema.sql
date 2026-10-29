do $verify$
declare
  first_party_tables_in_public text;
  first_party_routines_in_public text;
  missing_current_tables text;
  legacy_tables text;
begin
  select string_agg(format('%I.%I', n.nspname, c.relname), ', ' order by c.relname)
    into first_party_tables_in_public
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public'
    and c.relkind in ('r', 'p', 'v', 'm', 'f')
    and not exists (
      select 1 from pg_depend d
      where d.classid = 'pg_class'::regclass
        and d.objid = c.oid
        and d.deptype = 'e'
    );

  if first_party_tables_in_public is not null then
    raise exception 'First-party relations remain in public: %', first_party_tables_in_public;
  end if;

  select string_agg(p.proname, ', ' order by p.proname)
    into first_party_routines_in_public
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public'
    and p.prokind in ('f', 'p')
    and not exists (
      select 1 from pg_depend d
      where d.classid = 'pg_proc'::regclass
        and d.objid = p.oid
        and d.deptype = 'e'
    );

  if first_party_routines_in_public is not null then
    raise exception 'First-party routines remain in public: %', first_party_routines_in_public;
  end if;

  select string_agg(required.name, ', ' order by required.name)
    into missing_current_tables
  from unnest(array[
    'admin_user', 'admin_role', 'admin_permission', 'admin_role_permission',
    'admin_session_policy', 'audit_log', 'student', 'finance_category',
    'course', 'consent_policy', 'gallery_artwork', 'public_privacy_policy'
  ]) as required(name)
  where to_regclass(format('rami_art_studio.%I', required.name)) is null;

  if missing_current_tables is not null then
    raise exception 'Current administrator relations are missing from rami_art_studio: %', missing_current_tables;
  end if;

  select string_agg(legacy.name, ', ' order by legacy.name)
    into legacy_tables
  from unnest(array[
    'blog_posts', 'contacts', 'page_sections', 'site_settings'
  ]) as legacy(name)
  where to_regclass(format('rami_art_studio.%I', legacy.name)) is not null;

  if legacy_tables is not null then
    raise exception 'Unused legacy relations remain in rami_art_studio: %', legacy_tables;
  end if;

  if to_regprocedure('rami_art_studio.security_maintenance_allowed()') is null then
    raise exception 'Security maintenance guard function is missing from rami_art_studio';
  end if;

  if exists (
    select 1
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'rami_art_studio'
      and p.prosrc like '%public.%'
  ) then
    raise exception 'A moved routine still contains a public-qualified application reference';
  end if;

  if not exists (
    select 1 from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'rami_art_studio'
      and c.relname = 'admin_user'
      and c.relrowsecurity
  ) then
    raise exception 'Row-level security is not enabled on rami_art_studio.admin_user';
  end if;

  if not exists (
    select 1 from pg_trigger t
    join pg_class c on c.oid = t.tgrelid
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'rami_art_studio'
      and c.relname = 'student'
      and not t.tgisinternal
  ) then
    raise exception 'Expected student integrity triggers are missing from rami_art_studio';
  end if;
end
$verify$;
