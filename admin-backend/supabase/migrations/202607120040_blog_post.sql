set search_path = public, extensions;
create table public.blog_post (
  id uuid primary key,
  post_id uuid not null,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  visible boolean not null default false,
  based_on_revision_id uuid references public.blog_post(id) on update restrict on delete restrict,
  title varchar(100),
  summary varchar(300),
  category varchar(30),
  media_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  alt_text varchar(300),
  author_id uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_blog_post_revision unique(post_id,revision),
  constraint ck_blog_post_revision check(revision>=1),
  constraint ck_blog_post_status check(status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_blog_post_category check(category is null or category in ('CLASS_STORY','STUDIO_NEWS','ARTWORK_STORY')),
  constraint ck_blog_post_version check(version>=0),
  constraint ck_blog_post_lengths check(
    (title is null or char_length(btrim(title)) between 1 and 100)
    and (summary is null or char_length(btrim(summary)) between 1 and 300)
    and (alt_text is null or char_length(btrim(alt_text)) between 1 and 300)
  ),
  constraint ck_blog_post_publication check(
    (status='DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_blog_post_visible_published check(
    status<>'PUBLISHED' or not visible or (
      title is not null and summary is not null and category is not null
      and media_asset_id is not null and alt_text is not null
    )
  ),
  constraint ck_blog_post_hidden_published check(
    status<>'PUBLISHED' or visible or (title is not null and category is not null)
  )
);
create unique index uq_blog_post_one_draft on public.blog_post(post_id) where status='DRAFT';
create unique index uq_blog_post_one_published on public.blog_post(post_id) where status='PUBLISHED';
create index ix_blog_post_admin on public.blog_post(status,updated_at desc,post_id);
create index ix_blog_post_public on public.blog_post(status,visible,published_at desc,post_id desc);
create index ix_blog_post_category_public on public.blog_post(category,status,visible,published_at desc);
create index ix_blog_post_author on public.blog_post(author_id,updated_at desc);
create trigger tr_blog_post_updated_at before update on public.blog_post
  for each row execute function public.set_updated_at();
create or replace function public.guard_blog_post_history()
returns trigger language plpgsql set search_path = '' as $$
begin
  if old.status in ('PUBLISHED','ARCHIVED') then
    if new.post_id is distinct from old.post_id or new.revision is distinct from old.revision
      or new.visible is distinct from old.visible or new.based_on_revision_id is distinct from old.based_on_revision_id
      or new.title is distinct from old.title or new.summary is distinct from old.summary
      or new.category is distinct from old.category or new.media_asset_id is distinct from old.media_asset_id
      or new.alt_text is distinct from old.alt_text or new.author_id is distinct from old.author_id
      or new.created_by is distinct from old.created_by or new.published_by is distinct from old.published_by
      or new.published_at is distinct from old.published_at then
      raise exception using errcode='23514',constraint='ck_blog_published_immutable',
        message='published blog post content is immutable';
    end if;
    if old.status='ARCHIVED' and new.status<>'ARCHIVED' then
      raise exception using errcode='23514',constraint='ck_blog_archived_terminal',
        message='archived blog post is terminal';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_blog_post_history_guard before update on public.blog_post
  for each row execute function public.guard_blog_post_history();
create or replace function public.validate_blog_post_media()
returns trigger language plpgsql set search_path = '' as $$
declare asset_status varchar(20);
begin
  if new.media_asset_id is not null then
    select status into asset_status from public.media_asset where id=new.media_asset_id;
    if asset_status is distinct from 'READY' then
      raise exception using errcode='23514',constraint='ck_blog_media_ready',
        message='blog post media must be READY';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_blog_post_media before insert or update of media_asset_id on public.blog_post
  for each row execute function public.validate_blog_post_media();
revoke all on table public.blog_post from public,anon,authenticated;
grant select,insert,update on public.blog_post to rami_backend;
alter table public.blog_post enable row level security;
create policy blog_post_backend_all on public.blog_post for all to rami_backend using(true) with check(true);
comment on table public.blog_post is 'Stable blog card aggregates with editable DRAFT and immutable publication history.';
reset search_path;
