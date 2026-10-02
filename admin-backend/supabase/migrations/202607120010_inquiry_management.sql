set search_path = public, extensions;
create table public.inquiry (
  id uuid primary key,
  name_ciphertext bytea not null,
  name_hash char(64) not null,
  phone_ciphertext bytea not null,
  phone_hash char(64) not null,
  phone_last4 char(4) not null,
  interested_course_id uuid references public.course(id) on update restrict on delete restrict,
  message_ciphertext bytea not null,
  status varchar(20) not null default 'RECEIVED',
  consent_policy_version varchar(30) not null,
  consented_at timestamptz not null default statement_timestamp(),
  received_at timestamptz not null default statement_timestamp(),
  read_at timestamptz,
  read_by uuid references public.admin_user(id) on update restrict on delete restrict,
  notification_status varchar(20) not null default 'PENDING',
  notification_attempted_at timestamptz,
  version bigint not null default 0,
  retention_expires_at timestamptz not null default (statement_timestamp() + interval '3 years'),
  constraint ck_inquiry_name_hash check (name_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_inquiry_phone_hash check (phone_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_inquiry_phone_last4 check (phone_last4 ~ '^[0-9]{4}$'),
  constraint ck_inquiry_status check (
    status in ('RECEIVED', 'CONTACTING', 'COMPLETED', 'UNREACHABLE')
  ),
  constraint ck_inquiry_read_receipt check (
    (read_at is null and read_by is null) or (read_at is not null and read_by is not null)
  ),
  constraint ck_inquiry_notification check (
    (notification_status = 'PENDING' and notification_attempted_at is null)
    or (notification_status in ('SENT', 'FAILED') and notification_attempted_at is not null)
  ),
  constraint ck_inquiry_consent_policy check (
    char_length(btrim(consent_policy_version)) between 1 and 30
  ),
  constraint ck_inquiry_version check (version >= 0),
  constraint ck_inquiry_retention check (retention_expires_at > received_at)
);
create index ix_inquiry_status_received_id on public.inquiry (status, received_at, id);
create index ix_inquiry_read_received on public.inquiry (read_at, received_at);
create index ix_inquiry_course_status on public.inquiry (interested_course_id, status);
create index ix_inquiry_name_hash on public.inquiry (name_hash);
create index ix_inquiry_phone_hash on public.inquiry (phone_hash);
create index ix_inquiry_retention on public.inquiry (retention_expires_at);
create table public.inquiry_activity (
  id uuid primary key,
  inquiry_id uuid not null references public.inquiry(id) on update restrict on delete cascade,
  from_status varchar(20) not null,
  to_status varchar(20) not null,
  note_ciphertext bytea not null,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  inquiry_version bigint not null,
  constraint ck_inquiry_activity_from_status check (
    from_status in ('RECEIVED', 'CONTACTING', 'COMPLETED', 'UNREACHABLE')
  ),
  constraint ck_inquiry_activity_to_status check (
    to_status in ('RECEIVED', 'CONTACTING', 'COMPLETED', 'UNREACHABLE')
  ),
  constraint ck_inquiry_activity_changed check (from_status <> to_status),
  constraint ck_inquiry_activity_version check (inquiry_version > 0),
  constraint uq_inquiry_activity_version unique (inquiry_id, inquiry_version)
);
create index ix_inquiry_activity_timeline
  on public.inquiry_activity (inquiry_id, created_at, id);
create index ix_inquiry_activity_actor
  on public.inquiry_activity (created_by, created_at desc);
-- Public rate-limit identifiers are HMAC values; raw IP addresses and phone numbers are never stored.
create table public.inquiry_rate_limit_bucket (
  bucket_type varchar(20) not null,
  bucket_hash char(64) not null,
  window_started_at timestamptz not null,
  request_count integer not null default 1,
  updated_at timestamptz not null default statement_timestamp(),
  primary key (bucket_type, bucket_hash, window_started_at),
  constraint ck_inquiry_rate_bucket_type check (bucket_type in ('IP_HOUR', 'PHONE_DAY')),
  constraint ck_inquiry_rate_bucket_hash check (bucket_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_inquiry_rate_request_count check (request_count between 1 and 100000)
);
create index ix_inquiry_rate_limit_expiry
  on public.inquiry_rate_limit_bucket (window_started_at, bucket_type);
reset search_path;
revoke all on table
  public.inquiry,
  public.inquiry_activity,
  public.inquiry_rate_limit_bucket
from public, anon, authenticated;
grant select, insert, update, delete on public.inquiry to rami_backend;
grant select, insert on public.inquiry_activity to rami_backend;
grant select, insert, update, delete on public.inquiry_rate_limit_bucket to rami_backend;
alter table public.inquiry enable row level security;
alter table public.inquiry_activity enable row level security;
alter table public.inquiry_rate_limit_bucket enable row level security;
create policy inquiry_backend_all on public.inquiry
  for all to rami_backend using (true) with check (true);
create policy inquiry_activity_backend_select on public.inquiry_activity
  for select to rami_backend using (true);
create policy inquiry_activity_backend_insert on public.inquiry_activity
  for insert to rami_backend with check (true);
create policy inquiry_rate_limit_backend_all on public.inquiry_rate_limit_bucket
  for all to rami_backend using (true) with check (true);
