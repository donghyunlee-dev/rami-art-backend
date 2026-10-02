set search_path = public, extensions;
create table public.notification_batch (
  id uuid primary key,
  type varchar(30) not null,
  channel varchar(20) not null,
  recipient_filter_ciphertext bytea,
  subject_template_ciphertext bytea,
  body_template_ciphertext bytea,
  variables_ciphertext bytea,
  preview_fingerprint char(64),
  eligible_count integer not null default 0,
  missing_contact_count integer not null default 0,
  consent_excluded_count integer not null default 0,
  deduplicated_count integer not null default 0,
  scheduled_at timestamptz not null,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_notification_batch_type check (
    type in ('LESSON_NOTICE','PAYMENT_DUE','OVERDUE','GENERAL')
  ),
  constraint ck_notification_batch_channel check (
    channel in ('EMAIL','SMS','KAKAO','MANUAL')
  ),
  constraint ck_notification_batch_fingerprint check (
    preview_fingerprint is null or preview_fingerprint ~ '^[0-9a-f]{64}$'
  ),
  constraint ck_notification_batch_counts check (
    eligible_count >= 0 and missing_contact_count >= 0
    and consent_excluded_count >= 0 and deduplicated_count >= 0
  )
);
insert into public.notification_batch(
  id,type,channel,scheduled_at,created_by,created_at,eligible_count)
select distinct on (batch_key)
  batch_key,type,channel,scheduled_at,created_by,created_at,
  count(*) over (partition by batch_key)
from public.notification_message
order by batch_key,created_at,id;
alter table public.notification_message
  add column optional_notice boolean not null default false,
  add column variables_ciphertext bytea,
  add column last_attempt_at timestamptz,
  add column cancelled_at timestamptz,
  add column cancelled_by uuid references public.admin_user(id) on update restrict on delete restrict,
  add column cancel_reason varchar(200),
  add column version bigint not null default 0;
alter table public.notification_message
  add constraint fk_notification_batch foreign key (batch_key)
    references public.notification_batch(id) on update restrict on delete restrict,
  add constraint ck_notification_message_version check (version >= 0),
  add constraint ck_notification_cancel_reason check (
    cancel_reason is null or char_length(btrim(cancel_reason)) between 5 and 200
  );
create table public.notification_attempt (
  id uuid primary key,
  notification_message_id uuid not null
    references public.notification_message(id) on update restrict on delete restrict,
  attempt_number smallint not null,
  result varchar(30) not null,
  provider varchar(50),
  provider_message_id varchar(200),
  error_code varchar(80),
  started_at timestamptz not null,
  completed_at timestamptz not null,
  constraint uq_notification_attempt_number unique(notification_message_id,attempt_number),
  constraint ck_notification_attempt_number check (attempt_number between 1 and 5),
  constraint ck_notification_attempt_result check (
    result in ('SUCCESS','TRANSIENT_FAILURE','PERMANENT_FAILURE')
  ),
  constraint ck_notification_attempt_time check (completed_at >= started_at),
  constraint ck_notification_attempt_outcome check (
    (result='SUCCESS' and provider is not null and provider_message_id is not null and error_code is null)
    or (result<>'SUCCESS' and provider_message_id is null and error_code is not null)
  )
);
create index ix_notification_batch_created
  on public.notification_batch(created_at desc,id desc);
create index ix_notification_attempt_message
  on public.notification_attempt(notification_message_id,attempt_number);
revoke all on table public.notification_batch,public.notification_attempt
  from public,anon,authenticated;
grant select,insert,update on public.notification_batch,public.notification_attempt
  to rami_backend;
alter table public.notification_batch enable row level security;
alter table public.notification_attempt enable row level security;
create policy notification_batch_backend_all on public.notification_batch
  for all to rami_backend using (true) with check (true);
create policy notification_attempt_backend_all on public.notification_attempt
  for all to rami_backend using (true) with check (true);
comment on table public.notification_batch is
  'Encrypted notification composition snapshot and preview exclusion counts.';
comment on table public.notification_attempt is
  'Safe provider attempt timeline without recipient or rendered payload.';
