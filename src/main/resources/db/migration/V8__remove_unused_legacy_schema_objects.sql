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

do $cleanup$
begin
  if to_regprocedure('rami_art_studio.set_updated_at()') is not null
    and not exists (
      select 1
      from pg_trigger t
      where t.tgfoid = to_regprocedure('rami_art_studio.set_updated_at()')
        and not t.tgisinternal
    ) then
    drop function rami_art_studio.set_updated_at();
  end if;
end
$cleanup$;
