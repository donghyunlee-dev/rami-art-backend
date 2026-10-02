set search_path = public, extensions;
create table public.schedule_slot (
  id uuid primary key,
  class_group_id uuid not null references public.class_group(id) on update restrict on delete restrict,
  status varchar(20) not null default 'ACTIVE',
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  retired_at timestamptz,
  constraint ck_schedule_slot_status check (status in ('ACTIVE', 'RETIRED')),
  constraint ck_schedule_slot_retired check (
    (status = 'ACTIVE' and retired_at is null)
    or (status = 'RETIRED' and retired_at is not null)
  )
);
create index ix_schedule_slot_status_id on public.schedule_slot (status, id);
create index ix_schedule_slot_class_status_id
  on public.schedule_slot (class_group_id, status, id);
create index ix_schedule_slot_created_at on public.schedule_slot (created_at desc);
create table public.student_schedule_assignment (
  id uuid primary key,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  schedule_slot_id uuid not null references public.schedule_slot(id) on update restrict on delete restrict,
  effective_from date not null,
  effective_to date,
  ended_reason varchar(200),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_student_schedule_assignment_period check (
    effective_to is null or effective_to >= effective_from
  ),
  constraint ck_student_schedule_assignment_reason check (
    ended_reason is null or char_length(btrim(ended_reason)) between 1 and 200
  ),
  constraint ck_student_schedule_assignment_version check (version >= 0),
  constraint ex_student_schedule_assignment_period exclude using gist (
    student_id with =,
    schedule_slot_id with =,
    daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') with &&
  )
);
create index ix_student_schedule_assignment_student_period
  on public.student_schedule_assignment (student_id, effective_from, effective_to);
create index ix_student_schedule_assignment_slot_period
  on public.student_schedule_assignment (schedule_slot_id, effective_from, effective_to);
create trigger tr_student_schedule_assignment_updated_at
  before update on public.student_schedule_assignment
  for each row execute function public.set_updated_at();
create table public.consent_policy (
  id uuid primary key,
  type varchar(30) not null,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  title varchar(200) not null,
  body text not null,
  required boolean not null default false,
  valid_days integer,
  evidence_required boolean not null default false,
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_consent_policy_id_type unique (id, type),
  constraint uq_consent_policy_type_revision unique (type, revision),
  constraint ck_consent_policy_type check (type in (
    'PERSONAL_DATA_REQUIRED', 'MEDIA_PUBLICATION', 'PORTRAIT', 'OPTIONAL_NOTIFICATION'
  )),
  constraint ck_consent_policy_revision check (revision >= 1),
  constraint ck_consent_policy_status check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  constraint ck_consent_policy_title check (char_length(btrim(title)) between 1 and 200),
  constraint ck_consent_policy_body check (char_length(body) between 1 and 20000),
  constraint ck_consent_policy_required check (
    type <> 'PERSONAL_DATA_REQUIRED' or required
  ),
  constraint ck_consent_policy_valid_days check (
    valid_days is null or valid_days between 1 and 3650
  ),
  constraint ck_consent_policy_publication check (
    (status = 'DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED', 'ARCHIVED') and published_by is not null and published_at is not null)
  ),
  constraint ck_consent_policy_version check (version >= 0)
);
create unique index uq_consent_policy_one_draft
  on public.consent_policy (type) where status = 'DRAFT';
create unique index uq_consent_policy_one_published
  on public.consent_policy (type) where status = 'PUBLISHED';
create index ix_consent_policy_type_status_revision
  on public.consent_policy (type, status, revision desc);
create trigger tr_consent_policy_updated_at before update on public.consent_policy
  for each row execute function public.set_updated_at();
create table public.student_consent (
  id uuid primary key,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  consent_policy_id uuid not null,
  policy_type varchar(30) not null,
  guardian_contact_id uuid not null,
  method varchar(20) not null,
  status varchar(20) not null default 'ACTIVE',
  consented_at timestamptz not null,
  expires_on date,
  evidence_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  revoked_at timestamptz,
  revoke_reason varchar(300),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  revoked_by uuid references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  constraint fk_student_consent_policy foreign key (consent_policy_id, policy_type)
    references public.consent_policy(id, type) on update restrict on delete restrict,
  constraint fk_student_consent_guardian foreign key (guardian_contact_id, student_id)
    references public.guardian_contact(id, student_id) on update restrict on delete restrict,
  constraint ck_student_consent_method check (method in ('PAPER', 'DIGITAL', 'MIGRATED')),
  constraint ck_student_consent_status check (status in ('ACTIVE', 'REVOKED', 'EXPIRED')),
  constraint ck_student_consent_revoked check (
    (status <> 'REVOKED' and revoked_at is null and revoke_reason is null and revoked_by is null)
    or (status = 'REVOKED' and revoked_at is not null and revoked_by is not null
      and revoke_reason is not null and char_length(btrim(revoke_reason)) between 5 and 300)
  )
);
create unique index uq_student_consent_active_type
  on public.student_consent (student_id, policy_type) where status = 'ACTIVE';
create index ix_student_consent_student_status_expiry
  on public.student_consent (student_id, status, expires_on);
create index ix_student_consent_policy_status
  on public.student_consent (consent_policy_id, status);
create table public.enrollment_case (
  id uuid primary key,
  inquiry_id uuid unique references public.inquiry(id) on update restrict on delete restrict,
  lead_name_ciphertext bytea not null,
  phone_ciphertext bytea not null,
  phone_hash char(64) not null,
  phone_last4 char(4) not null,
  status varchar(30) not null default 'NEW',
  desired_course_id uuid references public.course(id) on update restrict on delete restrict,
  desired_class_group_id uuid references public.class_group(id) on update restrict on delete restrict,
  trial_starts_at timestamptz,
  waitlisted_at timestamptz,
  student_id uuid unique references public.student(id) on update restrict on delete restrict,
  lost_reason varchar(200),
  version bigint not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_enrollment_case_phone_hash check (phone_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_enrollment_case_phone_last4 check (phone_last4 ~ '^[0-9]{4}$'),
  constraint ck_enrollment_case_status check (status in (
    'NEW', 'CONTACTED', 'TRIAL_SCHEDULED', 'TRIAL_COMPLETED',
    'WAITLISTED', 'ENROLLED', 'LOST'
  )),
  constraint ck_enrollment_case_trial check (
    status not in ('TRIAL_SCHEDULED', 'TRIAL_COMPLETED') or trial_starts_at is not null
  ),
  constraint ck_enrollment_case_waitlist check (
    status <> 'WAITLISTED' or (waitlisted_at is not null and desired_class_group_id is not null)
  ),
  constraint ck_enrollment_case_student check (
    (status = 'ENROLLED' and student_id is not null)
    or (status <> 'ENROLLED' and student_id is null)
  ),
  constraint ck_enrollment_case_lost check (
    (status = 'LOST' and lost_reason is not null
      and char_length(btrim(lost_reason)) between 5 and 200)
    or (status <> 'LOST' and lost_reason is null)
  ),
  constraint ck_enrollment_case_version check (version >= 0)
);
create index ix_enrollment_case_status_updated_id
  on public.enrollment_case (status, updated_at desc, id);
create index ix_enrollment_case_phone_status
  on public.enrollment_case (phone_hash, status);
create index ix_enrollment_case_group_waitlist
  on public.enrollment_case (desired_class_group_id, status, waitlisted_at, id);
create index ix_enrollment_case_course_status
  on public.enrollment_case (desired_course_id, status, updated_at desc);
create trigger tr_enrollment_case_updated_at before update on public.enrollment_case
  for each row execute function public.set_updated_at();
create table public.enrollment_activity (
  id uuid primary key,
  enrollment_case_id uuid not null references public.enrollment_case(id) on update restrict on delete cascade,
  sequence bigint not null,
  type varchar(30) not null,
  from_status varchar(30),
  to_status varchar(30),
  channel varchar(20),
  outcome varchar(50),
  note_ciphertext bytea,
  occurred_at timestamptz not null default statement_timestamp(),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_enrollment_activity_sequence unique (enrollment_case_id, sequence),
  constraint ck_enrollment_activity_sequence check (sequence >= 1),
  constraint ck_enrollment_activity_type check (type in (
    'CREATED', 'CONTACT', 'TRIAL', 'WAITLIST', 'STATUS', 'ENROLLED', 'NOTE'
  )),
  constraint ck_enrollment_activity_statuses check (
    (from_status is null and to_status is null)
    or (from_status is not null and to_status is not null and from_status <> to_status
      and from_status in ('NEW','CONTACTED','TRIAL_SCHEDULED','TRIAL_COMPLETED','WAITLISTED','ENROLLED','LOST')
      and to_status in ('NEW','CONTACTED','TRIAL_SCHEDULED','TRIAL_COMPLETED','WAITLISTED','ENROLLED','LOST'))
  ),
  constraint ck_enrollment_activity_contact check (
    (type = 'CONTACT' and channel in ('PHONE','SMS','EMAIL','IN_PERSON')
      and outcome is not null and char_length(btrim(outcome)) between 1 and 50)
    or (type <> 'CONTACT' and channel is null and outcome is null)
  )
);
create index ix_enrollment_activity_timeline
  on public.enrollment_activity (enrollment_case_id, sequence desc);
create index ix_enrollment_activity_type_occurred
  on public.enrollment_activity (type, occurred_at desc);
reset search_path;
revoke all on table
  public.schedule_slot,
  public.student_schedule_assignment,
  public.consent_policy,
  public.student_consent,
  public.enrollment_case,
  public.enrollment_activity
from public, anon, authenticated;
grant select, insert, update on public.schedule_slot to rami_backend;
grant select, insert, update, delete on public.student_schedule_assignment to rami_backend;
grant select, insert, update on public.consent_policy to rami_backend;
grant select, insert, update on public.student_consent to rami_backend;
grant select, insert, update on public.enrollment_case to rami_backend;
grant select, insert on public.enrollment_activity to rami_backend;
alter table public.schedule_slot enable row level security;
alter table public.student_schedule_assignment enable row level security;
alter table public.consent_policy enable row level security;
alter table public.student_consent enable row level security;
alter table public.enrollment_case enable row level security;
alter table public.enrollment_activity enable row level security;
create policy schedule_slot_backend_all on public.schedule_slot
  for all to rami_backend using (true) with check (true);
create policy student_schedule_assignment_backend_all on public.student_schedule_assignment
  for all to rami_backend using (true) with check (true);
create policy consent_policy_backend_all on public.consent_policy
  for all to rami_backend using (true) with check (true);
create policy student_consent_backend_all on public.student_consent
  for all to rami_backend using (true) with check (true);
create policy enrollment_case_backend_select on public.enrollment_case
  for select to rami_backend using (true);
create policy enrollment_case_backend_insert on public.enrollment_case
  for insert to rami_backend with check (true);
create policy enrollment_case_backend_update on public.enrollment_case
  for update to rami_backend using (true) with check (true);
create policy enrollment_activity_backend_select on public.enrollment_activity
  for select to rami_backend using (true);
create policy enrollment_activity_backend_insert on public.enrollment_activity
  for insert to rami_backend with check (true);
