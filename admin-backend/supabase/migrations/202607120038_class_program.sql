set search_path = public, extensions;
create table public.class_program (
  id uuid primary key,
  course_id uuid not null references public.course(id) on update restrict on delete restrict,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  visible boolean not null default false,
  based_on_program_id uuid references public.class_program(id) on update restrict on delete restrict,
  audience_label varchar(100),
  title varchar(100),
  description varchar(500),
  activities jsonb not null default '[]'::jsonb,
  media_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  alt_text varchar(300),
  display_order integer not null default 0,
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_class_program_course_revision unique(course_id,revision),
  constraint ck_class_program_revision check(revision >= 1),
  constraint ck_class_program_status check(status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_class_program_display_order check(display_order >= 0),
  constraint ck_class_program_version check(version >= 0),
  constraint ck_class_program_activities_array check(jsonb_typeof(activities)='array'),
  constraint ck_class_program_lengths check(
    (audience_label is null or char_length(btrim(audience_label)) between 1 and 100)
    and (title is null or char_length(btrim(title)) between 1 and 100)
    and (description is null or char_length(btrim(description)) between 1 and 500)
    and (alt_text is null or char_length(btrim(alt_text)) between 1 and 300)
  ),
  constraint ck_class_program_publication check(
    (status='DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_class_program_visible_published check(
    status<>'PUBLISHED' or not visible or (
      audience_label is not null and title is not null and description is not null
      and jsonb_array_length(activities) between 1 and 10
      and media_asset_id is not null and alt_text is not null
    )
  )
);
create unique index uq_class_program_one_draft_per_course
  on public.class_program(course_id) where status='DRAFT';
create unique index uq_class_program_one_published_per_course
  on public.class_program(course_id) where status='PUBLISHED';
create unique index uq_class_program_visible_published_order
  on public.class_program(display_order) where status='PUBLISHED' and visible;
create index ix_class_program_course_status
  on public.class_program(course_id,status,revision desc,id);
create index ix_class_program_public_order
  on public.class_program(status,visible,display_order,course_id);
create trigger tr_class_program_updated_at
  before update on public.class_program
  for each row execute function public.set_updated_at();
create or replace function public.validate_class_program_media()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.media_asset_id is not null and not exists (
    select 1 from public.media_asset
     where id=new.media_asset_id and status='READY'
  ) then
    raise exception using
      errcode='23514', constraint='ck_class_program_media_ready',
      message='class program media must be READY';
  end if;
  return new;
end;
$$;
create trigger tr_class_program_media_ready
  before insert or update of media_asset_id on public.class_program
  for each row execute function public.validate_class_program_media();
revoke all on table public.class_program from public,anon,authenticated;
grant select,insert,update on public.class_program to rami_backend;
alter table public.class_program enable row level security;
create policy class_program_backend_all on public.class_program
  for all to rami_backend using (true) with check (true);
comment on table public.class_program is
  'Course-local immutable published class-card revisions; only DRAFT rows are editable.';
reset search_path;
