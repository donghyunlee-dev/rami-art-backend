create extension if not exists pg_trgm with schema extensions;
create table public.student (
  id uuid primary key,
  student_name varchar(100) not null,
  student_name_search varchar(100) not null,
  school_name varchar(150),
  birthday date,
  status varchar(20) not null default 'ACTIVE',
  joined_at date not null,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  version bigint not null default 0,
  constraint ck_student_name check (char_length(btrim(student_name)) between 1 and 100),
  constraint ck_student_name_search check (
    char_length(student_name_search) between 1 and 100
    and student_name_search = lower(regexp_replace(btrim(student_name_search), '\s+', '', 'g'))
  ),
  constraint ck_student_school check (
    school_name is null or char_length(btrim(school_name)) between 1 and 150
  ),
  constraint ck_student_status check (status in ('ACTIVE', 'PAUSED', 'GRADUATED', 'DROPPED')),
  constraint ck_student_joined_after_birthday check (birthday is null or joined_at >= birthday),
  constraint ck_student_version check (version >= 0)
);
create index ix_student_status_name_id
  on public.student (status, student_name_search, id);
create index ix_student_joined_id
  on public.student (joined_at desc, id);
create index ix_student_birthday
  on public.student (birthday);
create index ix_student_name_trgm
  on public.student using gin (student_name extensions.gin_trgm_ops);
create index ix_student_school_trgm
  on public.student using gin (school_name extensions.gin_trgm_ops);
create trigger tr_student_updated_at before update on public.student
  for each row execute function public.set_updated_at();
create table public.guardian_contact (
  id uuid primary key,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  name varchar(100) not null,
  relationship varchar(20) not null,
  relationship_detail varchar(50),
  phone_ciphertext bytea not null,
  phone_hash char(64) not null,
  phone_last4 char(4) not null,
  email_ciphertext bytea,
  email_hash char(64),
  email_domain varchar(253),
  preferred_channel varchar(20) not null default 'SMS',
  primary_contact boolean not null default false,
  display_order integer not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_guardian_id_student unique (id, student_id),
  constraint uq_guardian_student_phone unique (student_id, phone_hash),
  constraint uq_guardian_student_order unique (student_id, display_order),
  constraint ck_guardian_name check (char_length(btrim(name)) between 1 and 100),
  constraint ck_guardian_relationship check (
    relationship in ('MOTHER', 'FATHER', 'GRANDPARENT', 'GUARDIAN', 'OTHER')
  ),
  constraint ck_guardian_relationship_detail check (
    (relationship = 'OTHER' and relationship_detail is not null
      and char_length(btrim(relationship_detail)) between 1 and 50)
    or (relationship <> 'OTHER' and relationship_detail is null)
  ),
  constraint ck_guardian_phone_hash check (phone_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_guardian_phone_last4 check (phone_last4 ~ '^[0-9]{4}$'),
  constraint ck_guardian_email_fields check (
    (email_ciphertext is null and email_hash is null and email_domain is null)
    or (email_ciphertext is not null and email_hash is not null and email_domain is not null)
  ),
  constraint ck_guardian_email_hash check (email_hash is null or email_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_guardian_channel check (preferred_channel in ('SMS', 'KAKAO', 'EMAIL', 'MANUAL')),
  constraint ck_guardian_email_channel check (preferred_channel <> 'EMAIL' or email_ciphertext is not null),
  constraint ck_guardian_display_order check (display_order >= 0)
);
create unique index uq_guardian_student_email
  on public.guardian_contact (student_id, email_hash) where email_hash is not null;
create unique index uq_guardian_student_primary
  on public.guardian_contact (student_id) where primary_contact;
create index ix_guardian_phone_hash on public.guardian_contact (phone_hash, student_id);
create trigger tr_guardian_updated_at before update on public.guardian_contact
  for each row execute function public.set_updated_at();
create table public.student_status_history (
  id uuid primary key,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  from_status varchar(20),
  to_status varchar(20) not null,
  effective_date date not null,
  reason varchar(200) not null,
  changed_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  changed_at timestamptz not null default statement_timestamp(),
  student_version bigint not null,
  constraint uq_student_status_version unique (student_id, student_version),
  constraint ck_student_status_history_from check (
    from_status is null or from_status in ('ACTIVE', 'PAUSED', 'GRADUATED', 'DROPPED')
  ),
  constraint ck_student_status_history_to check (
    to_status in ('ACTIVE', 'PAUSED', 'GRADUATED', 'DROPPED')
  ),
  constraint ck_student_status_history_changed check (from_status is null or from_status <> to_status),
  constraint ck_student_status_history_reason check (char_length(btrim(reason)) between 5 and 200),
  constraint ck_student_status_history_version check (student_version >= 0)
);
create index ix_student_status_history_timeline
  on public.student_status_history (student_id, changed_at desc, id desc);
create index ix_student_status_history_status_date
  on public.student_status_history (to_status, effective_date);
create table public.student_note (
  id uuid primary key,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  content_ciphertext bytea not null,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  hidden_at timestamptz,
  hidden_by uuid references public.admin_user(id) on update restrict on delete restrict,
  hidden_reason varchar(200),
  version bigint not null default 0,
  constraint ck_student_note_hidden check (
    (hidden_at is null and hidden_by is null and hidden_reason is null)
    or (hidden_at is not null and hidden_by is not null and hidden_reason is not null
      and char_length(btrim(hidden_reason)) between 5 and 200)
  ),
  constraint ck_student_note_version check (version >= 0)
);
create index ix_student_note_cursor
  on public.student_note (student_id, hidden_at, created_at desc, id desc);
create trigger tr_student_note_updated_at before update on public.student_note
  for each row execute function public.set_updated_at();
revoke all on table
  public.student,
  public.guardian_contact,
  public.student_status_history,
  public.student_note
from public, anon, authenticated;
grant select, insert, update on public.student to rami_backend;
grant select, insert, update, delete on public.guardian_contact to rami_backend;
grant select, insert on public.student_status_history to rami_backend;
grant select, insert, update on public.student_note to rami_backend;
alter table public.student enable row level security;
alter table public.guardian_contact enable row level security;
alter table public.student_status_history enable row level security;
alter table public.student_note enable row level security;
create policy student_backend_select on public.student
  for select to rami_backend using (true);
create policy student_backend_insert on public.student
  for insert to rami_backend with check (true);
create policy student_backend_update on public.student
  for update to rami_backend using (true) with check (true);
create policy guardian_backend_select on public.guardian_contact
  for select to rami_backend using (true);
create policy guardian_backend_insert on public.guardian_contact
  for insert to rami_backend with check (true);
create policy guardian_backend_update on public.guardian_contact
  for update to rami_backend using (true) with check (true);
create policy guardian_backend_delete on public.guardian_contact
  for delete to rami_backend using (true);
create policy student_status_history_backend_select on public.student_status_history
  for select to rami_backend using (true);
create policy student_status_history_backend_insert on public.student_status_history
  for insert to rami_backend with check (true);
create policy student_note_backend_select on public.student_note
  for select to rami_backend using (true);
create policy student_note_backend_insert on public.student_note
  for insert to rami_backend with check (true);
create policy student_note_backend_update on public.student_note
  for update to rami_backend using (true) with check (true);
