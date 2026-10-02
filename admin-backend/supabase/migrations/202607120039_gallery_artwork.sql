set search_path = public, extensions;
create table public.gallery_artwork (
  id uuid primary key,
  artwork_id uuid not null,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  visible boolean not null default false,
  based_on_revision_id uuid references public.gallery_artwork(id) on update restrict on delete restrict,
  title varchar(150),
  course_id uuid references public.course(id) on update restrict on delete restrict,
  audience_label varchar(100),
  medium varchar(100),
  description varchar(1000),
  media_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  alt_text varchar(300),
  student_consent_id uuid references public.student_consent(id) on update restrict on delete restrict,
  consent_exemption_reason varchar(300),
  featured boolean not null default false,
  featured_order integer,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_gallery_artwork_revision unique(artwork_id, revision),
  constraint ck_gallery_artwork_revision check(revision >= 1),
  constraint ck_gallery_artwork_status check(status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_gallery_artwork_version check(version >= 0),
  constraint ck_gallery_artwork_lengths check(
    (title is null or char_length(btrim(title)) between 1 and 150)
    and (audience_label is null or char_length(btrim(audience_label)) between 1 and 100)
    and (medium is null or char_length(btrim(medium)) between 1 and 100)
    and (description is null or char_length(btrim(description)) between 1 and 1000)
    and (alt_text is null or char_length(btrim(alt_text)) between 1 and 300)
    and (consent_exemption_reason is null
      or char_length(btrim(consent_exemption_reason)) between 5 and 300)
  ),
  constraint ck_gallery_artwork_featured check(
    (not featured and featured_order is null)
    or (featured and visible and featured_order >= 0)
  ),
  constraint ck_gallery_artwork_publication check(
    (status='DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_gallery_artwork_visible_published check(
    status<>'PUBLISHED' or not visible or (
      title is not null and course_id is not null and audience_label is not null
      and medium is not null and description is not null and media_asset_id is not null
      and alt_text is not null
      and ((student_consent_id is not null and consent_exemption_reason is null)
        or (student_consent_id is null and consent_exemption_reason is not null))
    )
  ),
  constraint ck_gallery_artwork_hidden_published check(
    status<>'PUBLISHED' or visible or (title is not null and course_id is not null)
  )
);
create unique index uq_gallery_artwork_one_draft
  on public.gallery_artwork(artwork_id) where status='DRAFT';
create unique index uq_gallery_artwork_one_published
  on public.gallery_artwork(artwork_id) where status='PUBLISHED';
create unique index uq_gallery_artwork_featured_order
  on public.gallery_artwork(featured_order)
  where status='PUBLISHED' and visible and featured;
create index ix_gallery_artwork_status_visible_updated
  on public.gallery_artwork(status,visible,updated_at desc,artwork_id);
create index ix_gallery_artwork_public
  on public.gallery_artwork(status,visible,published_at desc,artwork_id desc);
create index ix_gallery_artwork_course_public
  on public.gallery_artwork(course_id,status,visible,published_at desc);
create index ix_gallery_artwork_featured
  on public.gallery_artwork(featured,featured_order,artwork_id);
create trigger tr_gallery_artwork_updated_at
  before update on public.gallery_artwork
  for each row execute function public.set_updated_at();
create or replace function public.guard_gallery_artwork_history()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.status in ('PUBLISHED','ARCHIVED') then
    if new.artwork_id is distinct from old.artwork_id
      or new.revision is distinct from old.revision
      or new.visible is distinct from old.visible
      or new.based_on_revision_id is distinct from old.based_on_revision_id
      or new.title is distinct from old.title
      or new.course_id is distinct from old.course_id
      or new.audience_label is distinct from old.audience_label
      or new.medium is distinct from old.medium
      or new.description is distinct from old.description
      or new.media_asset_id is distinct from old.media_asset_id
      or new.alt_text is distinct from old.alt_text
      or new.student_consent_id is distinct from old.student_consent_id
      or new.consent_exemption_reason is distinct from old.consent_exemption_reason
      or new.featured is distinct from old.featured
      or new.featured_order is distinct from old.featured_order
      or new.created_by is distinct from old.created_by
      or new.published_by is distinct from old.published_by
      or new.published_at is distinct from old.published_at then
      raise exception using errcode='23514', constraint='ck_gallery_published_immutable',
        message='published gallery artwork content is immutable';
    end if;
    if old.status='ARCHIVED' and new.status<>'ARCHIVED' then
      raise exception using errcode='23514', constraint='ck_gallery_archived_terminal',
        message='archived gallery artwork is terminal';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_gallery_artwork_history_guard
  before update on public.gallery_artwork
  for each row execute function public.guard_gallery_artwork_history();
create or replace function public.validate_gallery_artwork_relations()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  asset_status varchar(20);
  consent_type varchar(30);
  consent_status varchar(20);
  consent_expiry date;
begin
  if new.media_asset_id is not null then
    select status into asset_status from public.media_asset where id=new.media_asset_id;
    if asset_status is distinct from 'READY' then
      raise exception using errcode='23514', constraint='ck_gallery_media_ready',
        message='gallery artwork media must be READY';
    end if;
  end if;

  if new.status='PUBLISHED' and new.visible and new.student_consent_id is not null then
    select policy_type,status,expires_on into consent_type,consent_status,consent_expiry
      from public.student_consent where id=new.student_consent_id;
    if consent_type is distinct from 'MEDIA_PUBLICATION'
      or consent_status is distinct from 'ACTIVE'
      or (consent_expiry is not null and consent_expiry < (statement_timestamp() at time zone 'Asia/Seoul')::date) then
      raise exception using errcode='23514', constraint='ck_gallery_consent_active',
        message='gallery artwork requires active MEDIA_PUBLICATION consent';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_gallery_artwork_relations
  before insert or update of status,visible,media_asset_id,student_consent_id
  on public.gallery_artwork
  for each row execute function public.validate_gallery_artwork_relations();
revoke all on table public.gallery_artwork from public,anon,authenticated;
grant select,insert,update on public.gallery_artwork to rami_backend;
alter table public.gallery_artwork enable row level security;
create policy gallery_artwork_backend_all on public.gallery_artwork
  for all to rami_backend using (true) with check (true);
comment on table public.gallery_artwork is
  'Stable artwork aggregates with editable DRAFT and immutable published revision history.';
reset search_path;
