set search_path = public, extensions;
alter table public.student_consent
  add column if not exists version bigint not null default 0;
alter table public.student_consent
  drop constraint if exists ck_student_consent_version;
alter table public.student_consent
  add constraint ck_student_consent_version check (version >= 0);
create or replace function public.guard_consent_policy_history()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.status in ('PUBLISHED', 'ARCHIVED') then
    if new.type is distinct from old.type
      or new.revision is distinct from old.revision
      or new.title is distinct from old.title
      or new.body is distinct from old.body
      or new.required is distinct from old.required
      or new.valid_days is distinct from old.valid_days
      or new.evidence_required is distinct from old.evidence_required
      or new.created_by is distinct from old.created_by
      or new.published_by is distinct from old.published_by
      or new.published_at is distinct from old.published_at then
      raise exception using
        errcode = '23514', constraint = 'ck_consent_policy_published_immutable',
        message = 'published consent policy content is immutable';
    end if;
    if old.status = 'ARCHIVED' and new.status <> 'ARCHIVED' then
      raise exception using
        errcode = '23514', constraint = 'ck_consent_policy_archived_terminal',
        message = 'archived consent policy is terminal';
    end if;
  end if;
  return new;
end;
$$;
drop trigger if exists tr_consent_policy_history_guard on public.consent_policy;
create trigger tr_consent_policy_history_guard
  before update on public.consent_policy
  for each row execute function public.guard_consent_policy_history();
create or replace function public.validate_student_consent()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  policy_status varchar(20);
  policy_valid_days integer;
  policy_evidence_required boolean;
  evidence_status varchar(20);
  expected_expiry date;
begin
  select status, valid_days, evidence_required
    into policy_status, policy_valid_days, policy_evidence_required
  from public.consent_policy
  where id = new.consent_policy_id and type = new.policy_type;

  if policy_status is null or policy_status not in ('PUBLISHED', 'ARCHIVED') then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_policy_published',
      message = 'student consent requires a published policy revision';
  end if;

  if new.consented_at > statement_timestamp() then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_not_future',
      message = 'consented_at cannot be in the future';
  end if;

  expected_expiry := case when policy_valid_days is null then null
    else (new.consented_at at time zone 'Asia/Seoul')::date + policy_valid_days end;
  if new.expires_on is distinct from expected_expiry then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_expiry_matches_policy',
      message = 'expires_on must match the policy validity period';
  end if;

  if policy_evidence_required and new.evidence_asset_id is null then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_evidence_required',
      message = 'evidence is required';
  end if;

  if new.evidence_asset_id is not null then
    select status into evidence_status from public.media_asset where id = new.evidence_asset_id;
    if evidence_status is distinct from 'READY' then
      raise exception using
        errcode = '23514', constraint = 'ck_student_consent_evidence_ready',
        message = 'evidence asset must be private READY media';
    end if;
  end if;

  return new;
end;
$$;
drop trigger if exists tr_student_consent_validation on public.student_consent;
create trigger tr_student_consent_validation
  before insert or update of consent_policy_id, policy_type, consented_at, expires_on, evidence_asset_id
  on public.student_consent
  for each row execute function public.validate_student_consent();
create table public.notification_message (
  id uuid primary key,
  batch_key uuid not null,
  type varchar(30) not null,
  channel varchar(20) not null,
  student_id uuid references public.student(id) on update restrict on delete restrict,
  guardian_contact_id uuid,
  recipient_ciphertext bytea,
  recipient_hash char(64),
  recipient_last4 varchar(8),
  subject_ciphertext bytea,
  body_ciphertext bytea not null,
  consent_id uuid references public.student_consent(id) on update restrict on delete restrict,
  status varchar(20) not null default 'QUEUED',
  scheduled_at timestamptz not null default statement_timestamp(),
  attempt_count smallint not null default 0,
  next_attempt_at timestamptz,
  provider varchar(50),
  provider_message_id varchar(200),
  last_error_code varchar(80),
  idempotency_scope char(64) not null unique,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  sent_at timestamptz,
  constraint fk_notification_guardian foreign key (guardian_contact_id, student_id)
    references public.guardian_contact(id, student_id) on update restrict on delete restrict,
  constraint ck_notification_type check (type in ('LESSON_NOTICE','PAYMENT_DUE','OVERDUE','GENERAL')),
  constraint ck_notification_channel check (channel in ('EMAIL','SMS','KAKAO','MANUAL')),
  constraint ck_notification_status check (status in ('QUEUED','SENDING','SENT','FAILED','CANCELLED')),
  constraint ck_notification_recipient check (
    (channel = 'MANUAL' and recipient_ciphertext is null and recipient_hash is null)
    or (channel <> 'MANUAL' and recipient_ciphertext is not null and recipient_hash is not null)
  ),
  constraint ck_notification_recipient_hash check (
    recipient_hash is null or recipient_hash ~ '^[0-9a-f]{64}$'
  ),
  constraint ck_notification_scope check (idempotency_scope ~ '^[0-9a-f]{64}$'),
  constraint ck_notification_attempts check (attempt_count between 0 and 5),
  constraint ck_notification_terminal check (
    (status = 'SENT' and sent_at is not null and provider is not null and provider_message_id is not null
      and last_error_code is null)
    or (status = 'FAILED' and sent_at is null and last_error_code is not null)
    or (status in ('QUEUED','SENDING','CANCELLED') and sent_at is null)
  )
);
create unique index uq_notification_provider_message
  on public.notification_message (channel, provider, provider_message_id)
  where provider is not null and provider_message_id is not null;
create index ix_notification_worker
  on public.notification_message (status, scheduled_at, next_attempt_at, id);
create index ix_notification_batch_status
  on public.notification_message (batch_key, status);
create index ix_notification_student_created
  on public.notification_message (student_id, created_at desc);
create index ix_notification_consent_queued
  on public.notification_message (consent_id, status)
  where consent_id is not null;
reset search_path;
revoke all on table public.notification_message from public, anon, authenticated;
grant select, insert, update on public.notification_message to rami_backend;
alter table public.notification_message enable row level security;
create policy notification_message_backend_all on public.notification_message
  for all to rami_backend using (true) with check (true);
