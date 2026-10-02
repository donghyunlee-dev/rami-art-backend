set search_path = public, extensions;
create table public.media_asset (
  id uuid primary key,
  storage_key varchar(500) not null unique,
  public_path varchar(500) not null unique,
  original_file_name varchar(255) not null,
  sha256 char(64) not null,
  mime_type varchar(50) not null,
  file_size bigint not null,
  width integer not null,
  height integer not null,
  status varchar(20) not null default 'PENDING',
  created_by uuid references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null default (statement_timestamp() + interval '7 days'),
  constraint ck_media_asset_file_name check (
    char_length(btrim(original_file_name)) between 1 and 255
    and original_file_name !~ '[\\/[:cntrl:]]'
  ),
  constraint ck_media_asset_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
  constraint ck_media_asset_mime check (mime_type in ('image/jpeg', 'image/png', 'image/webp')),
  constraint ck_media_asset_size check (file_size between 1 and 10485760),
  constraint ck_media_asset_dimensions check (width between 1 and 8000 and height between 1 and 8000),
  constraint ck_media_asset_status check (status in ('PENDING', 'READY', 'FAILED')),
  constraint ck_media_asset_expiry check (expires_at > created_at)
);
create index ix_media_asset_expiry_id on public.media_asset (expires_at, id);
create index ix_media_asset_hash_size on public.media_asset (sha256, file_size);
create table public.site_brand_config (
  id uuid primary key,
  revision integer not null unique,
  status varchar(20) not null default 'DRAFT',
  based_on_id uuid references public.site_brand_config(id) on update restrict on delete restrict,
  brand_name varchar(100) not null,
  short_name varchar(30) not null,
  logo_asset_id uuid not null references public.media_asset(id) on update restrict on delete restrict,
  logo_alt_text varchar(300) not null,
  favicon_asset_id uuid not null references public.media_asset(id) on update restrict on delete restrict,
  share_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  primary_color char(7) not null,
  accent_color char(7) not null,
  font_preset varchar(30) not null,
  canonical_host varchar(253) not null,
  default_title varchar(60) not null,
  default_description varchar(160) not null,
  instagram_url varchar(500),
  blog_url varchar(500),
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_site_brand_revision check (revision >= 1),
  constraint ck_site_brand_status check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  constraint ck_site_brand_publication check (
    (status = 'DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED', 'ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_site_brand_name check (char_length(btrim(brand_name)) between 1 and 100),
  constraint ck_site_brand_short_name check (char_length(btrim(short_name)) between 1 and 30),
  constraint ck_site_brand_logo_alt check (char_length(btrim(logo_alt_text)) between 1 and 300),
  constraint ck_site_brand_primary_color check (
    primary_color in ('#1F2937', '#1D4ED8', '#166534', '#7C2D12', '#6B21A8')
  ),
  constraint ck_site_brand_accent_color check (
    accent_color in ('#F59E0B', '#0D9488', '#DB2777', '#2563EB', '#DC2626')
  ),
  constraint ck_site_brand_font check (
    font_preset in ('SYSTEM_SANS', 'SERIF_CLASSIC', 'ROUNDED_SANS')
  ),
  constraint ck_site_brand_canonical_host check (
    canonical_host = lower(canonical_host)
    and canonical_host ~ '^https://[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?$'
  ),
  constraint ck_site_brand_title check (char_length(btrim(default_title)) between 1 and 60),
  constraint ck_site_brand_description check (
    char_length(btrim(default_description)) between 1 and 160
  ),
  constraint ck_site_brand_instagram check (
    instagram_url is null or instagram_url ~ '^https://[^/?#[:space:]]+(?:/[^[:space:]]*)?$'
  ),
  constraint ck_site_brand_blog check (
    blog_url is null or blog_url ~ '^https://[^/?#[:space:]]+(?:/[^[:space:]]*)?$'
  ),
  constraint ck_site_brand_version check (version >= 0)
);
create unique index uq_site_brand_one_draft on public.site_brand_config (status) where status = 'DRAFT';
create unique index uq_site_brand_one_published on public.site_brand_config (status) where status = 'PUBLISHED';
create index ix_site_brand_status_revision on public.site_brand_config (status, revision desc);
create index ix_site_brand_updated_at on public.site_brand_config (updated_at desc);
create trigger tr_site_brand_updated_at before update on public.site_brand_config
  for each row execute function public.set_updated_at();
create or replace function public.validate_site_brand_media()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  ready_count integer;
  favicon_width integer;
  favicon_height integer;
begin
  select count(*) into ready_count
  from public.media_asset
  where id in (new.logo_asset_id, new.favicon_asset_id, new.share_asset_id)
    and status = 'READY';

  if ready_count <> (
    select count(distinct asset_id)
    from unnest(array[new.logo_asset_id, new.favicon_asset_id, new.share_asset_id]) asset_id
    where asset_id is not null
  ) then
    raise exception using
      errcode = '23514', constraint = 'ck_site_brand_media_ready',
      message = 'site brand assets must be READY';
  end if;

  select width, height into favicon_width, favicon_height
  from public.media_asset where id = new.favicon_asset_id;
  if favicon_width <> favicon_height then
    raise exception using
      errcode = '23514', constraint = 'ck_site_brand_favicon_square',
      message = 'site brand favicon must be square';
  end if;
  return new;
end;
$$;
create trigger tr_site_brand_media_guard
  before insert or update of logo_asset_id, favicon_asset_id, share_asset_id
  on public.site_brand_config
  for each row execute function public.validate_site_brand_media();
create table public.media_asset_reference (
  asset_id uuid not null references public.media_asset(id) on update restrict on delete restrict,
  owner_type varchar(30) not null,
  owner_id uuid not null,
  field_name varchar(50) not null,
  reference_state varchar(20) not null default 'DRAFT',
  created_at timestamptz not null default statement_timestamp(),
  primary key (asset_id, owner_type, owner_id, field_name),
  constraint ck_media_reference_owner check (owner_type in (
    'STUDIO_PROFILE', 'DIRECTOR_PROFILE', 'CLASS_PROGRAM', 'GALLERY_ARTWORK',
    'BLOG_POST', 'SITE_BRAND_CONFIG', 'STUDENT_LESSON_RECORD', 'STUDENT_CONSENT'
  )),
  constraint ck_media_reference_state check (reference_state in ('DRAFT', 'PUBLISHED', 'PRIVATE')),
  constraint ck_site_brand_reference_field check (
    owner_type <> 'SITE_BRAND_CONFIG' or field_name in ('logo', 'favicon', 'shareImage')
  )
);
create index ix_media_reference_owner on public.media_asset_reference (owner_type, owner_id, field_name);
create index ix_media_reference_asset_state on public.media_asset_reference (asset_id, reference_state);
create table public.cache_invalidation_event (
  id uuid primary key,
  aggregate_type varchar(50) not null,
  aggregate_id uuid not null,
  revision integer not null,
  status varchar(20) not null default 'PENDING',
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_cache_invalidation_aggregate check (aggregate_type = 'SITE_BRAND_CONFIG'),
  constraint ck_cache_invalidation_revision check (revision >= 1),
  constraint ck_cache_invalidation_status check (status in ('PENDING', 'DELIVERED', 'FAILED'))
);
create index ix_cache_invalidation_status_created
  on public.cache_invalidation_event (status, created_at, id);
insert into public.media_asset (
  id, storage_key, public_path, original_file_name, sha256, mime_type,
  file_size, width, height, status, created_by, expires_at
) values
  (md5('seed:generic-brand-logo')::uuid, 'seed/generic-brand-logo.png',
   '/seed/generic-brand-logo.png', 'generic-brand-logo.png',
   encode(digest('generic-brand-logo', 'sha256'), 'hex'), 'image/png',
   1024, 1200, 400, 'READY', null, '9999-12-31 00:00:00+00'),
  (md5('seed:generic-brand-favicon')::uuid, 'seed/generic-brand-favicon.png',
   '/seed/generic-brand-favicon.png', 'generic-brand-favicon.png',
   encode(digest('generic-brand-favicon', 'sha256'), 'hex'), 'image/png',
   512, 512, 512, 'READY', null, '9999-12-31 00:00:00+00'),
  (md5('seed:generic-brand-share')::uuid, 'seed/generic-brand-share.png',
   '/seed/generic-brand-share.png', 'generic-brand-share.png',
   encode(digest('generic-brand-share', 'sha256'), 'hex'), 'image/png',
   2048, 1200, 630, 'READY', null, '9999-12-31 00:00:00+00');
reset search_path;
revoke all on table
  public.media_asset,
  public.site_brand_config,
  public.media_asset_reference,
  public.cache_invalidation_event
from public, anon, authenticated;
grant select, insert, update, delete on public.media_asset to rami_backend;
grant select, insert, update on public.site_brand_config to rami_backend;
grant select, insert, update, delete on public.media_asset_reference to rami_backend;
grant select, insert, update on public.cache_invalidation_event to rami_backend;
alter table public.media_asset enable row level security;
alter table public.site_brand_config enable row level security;
alter table public.media_asset_reference enable row level security;
alter table public.cache_invalidation_event enable row level security;
create policy media_asset_backend_all on public.media_asset
  for all to rami_backend using (true) with check (true);
create policy site_brand_config_backend_all on public.site_brand_config
  for all to rami_backend using (true) with check (true);
create policy media_asset_reference_backend_all on public.media_asset_reference
  for all to rami_backend using (true) with check (true);
create policy cache_invalidation_event_backend_all on public.cache_invalidation_event
  for all to rami_backend using (true) with check (true);
