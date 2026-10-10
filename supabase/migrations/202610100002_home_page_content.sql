set search_path = public, extensions;

alter table public.admin_permission drop constraint ck_admin_permission_domain;
alter table public.admin_permission add constraint ck_admin_permission_domain check (domain in (
  'DASHBOARD', 'STUDENT', 'SCHEDULE', 'ATTENDANCE', 'TUITION', 'TUITION_ADJUSTMENT',
  'TUITION_RECEIPT', 'FINANCE', 'CONTENT_PROFILE', 'CONTENT_PROGRAM', 'MEDIA',
  'GALLERY', 'BLOG', 'INQUIRY', 'SITE_BRAND', 'HOME_CONTENT', 'COURSE', 'STAFF',
  'LESSON_PLAN', 'LESSON_LOG', 'MAKEUP', 'ENROLLMENT', 'NOTIFICATION', 'CONSENT',
  'DATA_TRANSFER', 'RETENTION', 'ADMIN_ACCOUNT', 'SECURITY_POLICY', 'AUDIT'
));

insert into public.admin_permission (id, code, name, domain, operation, description)
values
  (gen_random_uuid(), 'HOME_CONTENT_READ', '홈 콘텐츠 조회', 'HOME_CONTENT', 'READ', '홈페이지 콘텐츠와 공개 후보 조회'),
  (gen_random_uuid(), 'HOME_CONTENT_WRITE', '홈 콘텐츠 편집', 'HOME_CONTENT', 'WRITE', '홈페이지 콘텐츠 초안 작성 및 변경'),
  (gen_random_uuid(), 'HOME_CONTENT_PUBLISH', '홈 콘텐츠 발행', 'HOME_CONTENT', 'PUBLISH', '홈페이지 콘텐츠 revision 발행')
on conflict (code) do update set active=true, name=excluded.name, domain=excluded.domain,
  operation=excluded.operation, description=excluded.description;

insert into public.admin_role_permission (admin_role_id, admin_permission_id)
select role.id, permission.id
from public.admin_role role
join public.admin_permission permission on permission.code in
  ('HOME_CONTENT_READ', 'HOME_CONTENT_WRITE', 'HOME_CONTENT_PUBLISH')
where role.code in ('OWNER', 'CONTENT')
on conflict do nothing;

create table public.home_page_content (
  id uuid primary key,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  based_on_revision_id uuid references public.home_page_content(id) on update restrict on delete restrict,
  hero_title varchar(100),
  hero_description varchar(300),
  hero_media_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  hero_alt_text varchar(300),
  hero_cta_label varchar(40),
  hero_cta_target varchar(30),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_home_page_content_revision unique(revision),
  constraint ck_home_page_content_revision check(revision >= 1),
  constraint ck_home_page_content_status check(status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_home_page_content_version check(version >= 0),
  constraint ck_home_page_content_cta check(hero_cta_target is null or hero_cta_target in
    ('CONTACT_INQUIRY','CLASSES','GALLERY_WORKS','BLOG')),
  constraint ck_home_page_content_lengths check(
    (hero_title is null or char_length(btrim(hero_title)) between 1 and 100)
    and (hero_description is null or char_length(btrim(hero_description)) between 1 and 300)
    and (hero_alt_text is null or char_length(btrim(hero_alt_text)) between 1 and 300)
    and (hero_cta_label is null or char_length(btrim(hero_cta_label)) between 1 and 40)
  ),
  constraint ck_home_page_content_publication check(
    (status='DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_home_page_content_publishable_fields check(
    status='DRAFT' or (hero_title is not null and hero_description is not null
      and hero_media_asset_id is not null and hero_alt_text is not null
      and hero_cta_label is not null and hero_cta_target is not null)
  )
);
create unique index uq_home_page_content_one_draft on public.home_page_content(status) where status='DRAFT';
create unique index uq_home_page_content_one_published on public.home_page_content(status) where status='PUBLISHED';
create index ix_home_page_content_history on public.home_page_content(revision desc);
create trigger tr_home_page_content_updated_at before update on public.home_page_content
  for each row execute function public.set_updated_at();

create table public.home_page_strength (
  content_id uuid not null references public.home_page_content(id) on update restrict on delete restrict,
  display_order smallint not null,
  icon_code varchar(20) not null,
  title varchar(80) not null,
  description varchar(300) not null,
  constraint pk_home_page_strength primary key(content_id, display_order),
  constraint ck_home_page_strength_order check(display_order between 0 and 5),
  constraint ck_home_page_strength_icon check(icon_code in ('PALETTE','USERS','SPARKLES')),
  constraint ck_home_page_strength_text check(
    char_length(btrim(title)) between 1 and 80 and char_length(btrim(description)) between 1 and 300)
);

create table public.home_page_section (
  content_id uuid not null references public.home_page_content(id) on update restrict on delete restrict,
  section_key varchar(20) not null,
  visible boolean not null,
  display_order smallint not null,
  constraint pk_home_page_section primary key(content_id, section_key),
  constraint uq_home_page_section_order unique(content_id, display_order),
  constraint ck_home_page_section_key check(section_key in
    ('HERO','STRENGTHS','PROGRAMS','GALLERY','BLOG','LOCATION','CONTACT')),
  constraint ck_home_page_section_order check(display_order between 0 and 6)
);

create table public.home_page_reference (
  content_id uuid not null references public.home_page_content(id) on update restrict on delete restrict,
  reference_type varchar(20) not null,
  target_id uuid not null,
  display_order smallint not null,
  constraint pk_home_page_reference primary key(content_id, reference_type, display_order),
  constraint uq_home_page_reference_target unique(content_id, reference_type, target_id),
  constraint ck_home_page_reference_type check(reference_type in ('COURSE','ARTWORK','BLOG_POST')),
  constraint ck_home_page_reference_order check(display_order between 0 and 3)
);
create index ix_home_page_reference_target on public.home_page_reference(reference_type,target_id);

alter table public.media_asset_reference drop constraint ck_media_reference_owner;
alter table public.media_asset_reference add constraint ck_media_reference_owner check (owner_type in (
  'STUDIO_PROFILE', 'DIRECTOR_PROFILE', 'CLASS_PROGRAM', 'GALLERY_ARTWORK',
  'BLOG_POST', 'SITE_BRAND_CONFIG', 'HOME_PAGE_CONTENT',
  'STUDENT_LESSON_RECORD', 'STUDENT_CONSENT'
));

create or replace function public.guard_home_page_content_history()
returns trigger language plpgsql set search_path = '' as $$
begin
  if old.status in ('PUBLISHED','ARCHIVED') then
    if new.id is distinct from old.id or new.revision is distinct from old.revision
      or new.based_on_revision_id is distinct from old.based_on_revision_id
      or new.hero_title is distinct from old.hero_title or new.hero_description is distinct from old.hero_description
      or new.hero_media_asset_id is distinct from old.hero_media_asset_id
      or new.hero_alt_text is distinct from old.hero_alt_text
      or new.hero_cta_label is distinct from old.hero_cta_label
      or new.hero_cta_target is distinct from old.hero_cta_target
      or new.created_by is distinct from old.created_by or new.published_by is distinct from old.published_by
      or new.published_at is distinct from old.published_at then
      raise exception using errcode='23514', constraint='ck_home_page_content_immutable',
        message='published home page content is immutable';
    end if;
    if old.status='ARCHIVED' and new.status<>'ARCHIVED' then
      raise exception using errcode='23514', constraint='ck_home_page_content_archived_terminal',
        message='archived home page content is terminal';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_home_page_content_history_guard before update on public.home_page_content
  for each row execute function public.guard_home_page_content_history();

create or replace function public.guard_home_page_child_draft()
returns trigger language plpgsql set search_path = '' as $$
declare owner_id uuid; owner_status text;
begin
  owner_id := coalesce(new.content_id, old.content_id);
  select status into owner_status from public.home_page_content where id=owner_id;
  if owner_status is distinct from 'DRAFT' then
    raise exception using errcode='23514', constraint='ck_home_page_child_draft_only',
      message='home page child rows may only change while the owner is a draft';
  end if;
  if tg_op='DELETE' then return old; end if;
  return new;
end;
$$;
create trigger tr_home_page_strength_draft_only before insert or update or delete on public.home_page_strength
  for each row execute function public.guard_home_page_child_draft();
create trigger tr_home_page_section_draft_only before insert or update or delete on public.home_page_section
  for each row execute function public.guard_home_page_child_draft();
create trigger tr_home_page_reference_draft_only before insert or update or delete on public.home_page_reference
  for each row execute function public.guard_home_page_child_draft();

create or replace function public.sync_home_page_media_reference()
returns trigger language plpgsql set search_path = '' as $$
begin
  if tg_op='DELETE' then
    delete from public.media_asset_reference where owner_type='HOME_PAGE_CONTENT' and owner_id=old.id and field_name='heroImage';
    return old;
  end if;
  delete from public.media_asset_reference where owner_type='HOME_PAGE_CONTENT' and owner_id=new.id
    and field_name='heroImage' and asset_id<>new.hero_media_asset_id and reference_state='DRAFT';
  if new.hero_media_asset_id is null then
    delete from public.media_asset_reference where owner_type='HOME_PAGE_CONTENT' and owner_id=new.id and field_name='heroImage';
  else
    insert into public.media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state)
    values(new.hero_media_asset_id,'HOME_PAGE_CONTENT',new.id,'heroImage',case when new.status='PUBLISHED' then 'PUBLISHED' else 'DRAFT' end)
    on conflict(asset_id,owner_type,owner_id,field_name) do update set reference_state=excluded.reference_state;
  end if;
  return new;
end;
$$;
create trigger tr_home_page_media_reference after insert or update of hero_media_asset_id,status or delete
  on public.home_page_content for each row execute function public.sync_home_page_media_reference();

alter table public.home_page_content enable row level security;
alter table public.home_page_strength enable row level security;
alter table public.home_page_section enable row level security;
alter table public.home_page_reference enable row level security;
create policy home_page_content_backend_all on public.home_page_content for all to rami_backend using (true) with check (true);
create policy home_page_strength_backend_all on public.home_page_strength for all to rami_backend using (true) with check (true);
create policy home_page_section_backend_all on public.home_page_section for all to rami_backend using (true) with check (true);
create policy home_page_reference_backend_all on public.home_page_reference for all to rami_backend using (true) with check (true);
revoke all on table public.home_page_content,public.home_page_strength,public.home_page_section,public.home_page_reference from public,anon,authenticated;
grant select,insert,update on public.home_page_content to rami_backend;
grant select,insert,update,delete on public.home_page_strength,public.home_page_section,public.home_page_reference to rami_backend;

reset search_path;
