set search_path = public, extensions;
create table public.retention_hold (
  id uuid primary key,
  target_type varchar(30) not null,
  target_id uuid not null,
  reason varchar(500) not null,
  status varchar(20) not null default 'ACTIVE',
  starts_at timestamptz not null default statement_timestamp(),
  ends_at timestamptz,
  released_at timestamptz,
  release_reason varchar(500),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  released_by uuid references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_retention_hold_target check (target_type in (
    'INQUIRY','STUDENT','STUDENT_CONSENT','DATA_TRANSFER_JOB','NOTIFICATION_MESSAGE'
  )),
  constraint ck_retention_hold_reason check (char_length(btrim(reason)) between 10 and 500),
  constraint ck_retention_hold_status check (status in ('ACTIVE','RELEASED','EXPIRED')),
  constraint ck_retention_hold_ends check (ends_at is null or ends_at > starts_at),
  constraint ck_retention_hold_release check (
    (status='ACTIVE' and released_at is null and release_reason is null and released_by is null)
    or (status='EXPIRED' and released_at is null and release_reason is null and released_by is null)
    or (status='RELEASED' and released_at is not null and released_by is not null
      and release_reason is not null
      and char_length(btrim(release_reason)) between 10 and 500)
  )
);
create unique index uq_retention_hold_active_target
  on public.retention_hold(target_type,target_id) where status='ACTIVE';
create index ix_retention_hold_status_ends
  on public.retention_hold(status,ends_at,id);
create index ix_retention_hold_target
  on public.retention_hold(target_type,target_id,status);
create trigger tr_retention_hold_updated_at before update on public.retention_hold
  for each row execute function public.set_updated_at();
create table public.retention_run (
  id uuid primary key,
  domain varchar(30) not null,
  policy_version varchar(50) not null,
  cutoff_at timestamptz not null,
  preview_version uuid not null unique,
  status varchar(20) not null default 'PREVIEWED',
  candidate_count integer not null default 0,
  hold_excluded_count integer not null default 0,
  reference_excluded_count integer not null default 0,
  processed_count integer not null default 0,
  failed_count integer not null default 0,
  cursor varchar(200),
  failure_summary jsonb not null default '{}'::jsonb,
  approved_by uuid references public.admin_user(id) on update restrict on delete restrict,
  approved_at timestamptz,
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_retention_run_domain check (domain in (
    'INQUIRY','STUDENT_PRIVATE','CONSENT_EVIDENCE','TRANSFER_FILE','NOTIFICATION_PAYLOAD'
  )),
  constraint ck_retention_run_status check (
    status in ('PREVIEWED','PROCESSING','COMPLETED','PARTIAL','FAILED','STALE')
  ),
  constraint ck_retention_run_counts check (
    candidate_count >= 0 and hold_excluded_count >= 0 and reference_excluded_count >= 0
    and processed_count >= 0 and failed_count >= 0
    and processed_count + failed_count <= candidate_count
  ),
  constraint ck_retention_run_failure_summary check (jsonb_typeof(failure_summary)='object'),
  constraint ck_retention_run_approval check (
    (status in ('PREVIEWED','STALE') and approved_by is null and approved_at is null
      and started_at is null and completed_at is null)
    or (status='PROCESSING' and approved_by is not null and approved_at is not null
      and started_at is not null and completed_at is null)
    or (status in ('COMPLETED','PARTIAL','FAILED') and approved_by is not null
      and approved_at is not null and started_at is not null and completed_at is not null)
  )
);
create unique index uq_retention_run_domain_processing
  on public.retention_run(domain) where status='PROCESSING';
create index ix_retention_run_created
  on public.retention_run(created_at desc,id desc);
create trigger tr_retention_run_updated_at before update on public.retention_run
  for each row execute function public.set_updated_at();
alter table public.student
  add column private_purged_at timestamptz,
  alter column student_name drop not null,
  alter column student_name_search drop not null;
alter table public.guardian_contact
  alter column name drop not null,
  alter column phone_ciphertext drop not null,
  alter column phone_hash drop not null,
  alter column phone_last4 drop not null;
alter table public.student
  add constraint ck_student_private_purge_shape check (
    (private_purged_at is null and student_name is not null and student_name_search is not null)
    or (private_purged_at is not null and student_name is null and student_name_search is null
      and school_name is null and birthday is null)
  );
alter table public.student_consent
  add column evidence_purged_at timestamptz;
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
    raise exception using errcode='23514',
      constraint='ck_student_consent_policy_published',
      message='student consent requires a published policy revision';
  end if;
  if new.consented_at > statement_timestamp() then
    raise exception using errcode='23514', constraint='ck_student_consent_not_future',
      message='consented_at cannot be in the future';
  end if;
  expected_expiry := case when policy_valid_days is null then null
    else (new.consented_at at time zone 'Asia/Seoul')::date + policy_valid_days end;
  if new.expires_on is distinct from expected_expiry then
    raise exception using errcode='23514',
      constraint='ck_student_consent_expiry_matches_policy',
      message='expires_on must match the policy validity period';
  end if;
  if policy_evidence_required and new.evidence_asset_id is null
      and new.evidence_purged_at is null then
    raise exception using errcode='23514', constraint='ck_student_consent_evidence_required',
      message='evidence is required';
  end if;
  if new.evidence_asset_id is not null then
    select status into evidence_status from public.media_asset where id=new.evidence_asset_id;
    if evidence_status is distinct from 'READY' then
      raise exception using errcode='23514', constraint='ck_student_consent_evidence_ready',
        message='evidence asset must be private READY media';
    end if;
  end if;
  return new;
end;
$$;
alter table public.notification_message
  add column payload_purged_at timestamptz,
  alter column body_ciphertext drop not null;
alter table public.notification_message drop constraint ck_notification_recipient;
alter table public.notification_message add constraint ck_notification_recipient check (
  (payload_purged_at is not null and recipient_ciphertext is null and recipient_hash is null
    and recipient_last4 is null and subject_ciphertext is null and body_ciphertext is null
    and variables_ciphertext is null)
  or
  (payload_purged_at is null and body_ciphertext is not null and (
    (channel='MANUAL' and recipient_ciphertext is null and recipient_hash is null)
    or (channel<>'MANUAL' and recipient_ciphertext is not null and recipient_hash is not null)
  ))
);
alter table public.notification_batch
  add column payload_purged_at timestamptz;
create or replace function public.guard_retention_hold_history()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.target_type is distinct from new.target_type
      or old.target_id is distinct from new.target_id
      or old.reason is distinct from new.reason
      or old.starts_at is distinct from new.starts_at
      or old.ends_at is distinct from new.ends_at
      or old.created_by is distinct from new.created_by
      or old.created_at is distinct from new.created_at then
    raise exception using errcode='23514', message='retention hold identity is immutable';
  end if;
  if old.status <> 'ACTIVE' or new.status not in ('RELEASED','EXPIRED') then
    raise exception using errcode='23514', message='retention hold transition is invalid';
  end if;
  return new;
end;
$$;
create trigger tr_retention_hold_history_guard before update on public.retention_hold
  for each row execute function public.guard_retention_hold_history();
create or replace function public.prevent_retention_history_delete()
returns trigger language plpgsql set search_path='' as $$
begin
  raise exception using errcode='23514', message='retention history cannot be deleted';
end;
$$;
create trigger tr_retention_hold_no_delete before delete on public.retention_hold
  for each row execute function public.prevent_retention_history_delete();
create trigger tr_retention_run_no_delete before delete on public.retention_run
  for each row execute function public.prevent_retention_history_delete();
reset search_path;
revoke all on table public.retention_hold,public.retention_run
from public,anon,authenticated;
grant select,insert,update on public.retention_hold,public.retention_run to rami_backend;
grant delete on public.inquiry,public.inquiry_activity to rami_backend;
alter table public.retention_hold enable row level security;
alter table public.retention_run enable row level security;
create policy retention_hold_backend_all on public.retention_hold
  for all to rami_backend using(true) with check(true);
create policy retention_run_backend_all on public.retention_run
  for all to rami_backend using(true) with check(true);
comment on table public.retention_hold is
  'Permanent legal/operational hold history. Reasons must never contain private source data.';
comment on table public.retention_run is
  'Permanent aggregate-only preview and purge evidence without candidate identifiers.';
