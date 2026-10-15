create schema if not exists rami_art_studio;

drop table if exists
  rami_art_studio.admin_refresh_tokens,
  rami_art_studio.blog_posts,
  rami_art_studio.class_tags,
  rami_art_studio.classes,
  rami_art_studio.contacts,
  rami_art_studio.gallery_works,
  rami_art_studio.gallery_categories,
  rami_art_studio.page_sections,
  rami_art_studio.site_settings,
  rami_art_studio.admin_users
 cascade;

drop function if exists rami_art_studio.set_updated_at();

do $consolidate$
declare
  object_record record;
  routine_definition text;
  relation_command text;
begin
  for object_record in
    select c.relkind, c.relname
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind in ('r', 'p', 'v', 'm', 'f')
      and not exists (
        select 1
        from pg_depend d
        where d.classid = 'pg_class'::regclass
          and d.objid = c.oid
          and d.deptype = 'e'
      )
    order by c.relkind, c.relname
  loop
    relation_command := case object_record.relkind
      when 'r' then 'TABLE'
      when 'p' then 'TABLE'
      when 'v' then 'VIEW'
      when 'm' then 'MATERIALIZED VIEW'
      when 'f' then 'FOREIGN TABLE'
    end;
    execute format('alter %s public.%I set schema rami_art_studio', relation_command, object_record.relname);
  end loop;

  for object_record in
    select c.relname
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind = 'S'
      and not exists (
        select 1
        from pg_depend d
        where d.classid = 'pg_class'::regclass
          and d.objid = c.oid
          and d.deptype = 'e'
      )
    order by c.relname
  loop
    execute format('alter sequence public.%I set schema rami_art_studio', object_record.relname);
  end loop;

  for object_record in
    select t.typname
    from pg_type t
    join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'public'
      and t.typrelid = 0
      and t.typtype in ('d', 'e', 'r')
      and not exists (
        select 1
        from pg_depend d
        where d.classid = 'pg_type'::regclass
          and d.objid = t.oid
          and d.deptype = 'e'
      )
    order by t.typname
  loop
    execute format('alter type public.%I set schema rami_art_studio', object_record.typname);
  end loop;

  for object_record in
    select p.oid,
      p.proname,
      p.prokind,
      pg_get_function_identity_arguments(p.oid) as identity_arguments
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.prokind in ('f', 'p')
      and not exists (
        select 1
        from pg_depend d
        where d.classid = 'pg_proc'::regclass
          and d.objid = p.oid
          and d.deptype = 'e'
      )
    order by p.proname, p.oid
  loop
    routine_definition := pg_get_functiondef(object_record.oid);
    if object_record.prokind = 'p' then
      execute format(
        'alter procedure public.%I(%s) set schema rami_art_studio',
        object_record.proname,
        object_record.identity_arguments
      );
    else
      execute format(
        'alter function public.%I(%s) set schema rami_art_studio',
        object_record.proname,
        object_record.identity_arguments
      );
    end if;

    routine_definition := replace(routine_definition, 'public.', 'rami_art_studio.');
    routine_definition := regexp_replace(
      routine_definition,
      'search_path[[:space:]]+(TO|=)[[:space:]]+public',
      'search_path TO rami_art_studio',
      'gi'
    );
    execute routine_definition;
  end loop;
end
$consolidate$;

grant usage on schema rami_art_studio to rami_backend;
alter role rami_backend set search_path to rami_art_studio, extensions;
