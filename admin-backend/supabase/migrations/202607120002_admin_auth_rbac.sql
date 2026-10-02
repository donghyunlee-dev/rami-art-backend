create table public.admin_role (
  id uuid primary key,
  code varchar(20) not null unique,
  name varchar(50) not null unique,
  description varchar(200) not null,
  display_order smallint not null unique check (display_order between 1 and 4),
  active boolean not null default true,
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_admin_role_code check (code in ('OWNER', 'OPERATOR', 'CONTENT', 'FINANCE'))
);
create index ix_admin_role_active_order on public.admin_role (active, display_order);
create table public.admin_permission (
  id uuid primary key,
  code varchar(80) not null unique,
  name varchar(100) not null,
  domain varchar(30) not null,
  operation varchar(20) not null,
  description varchar(300) not null,
  active boolean not null default true,
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_admin_permission_code check (code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
  constraint ck_admin_permission_domain check (domain in (
    'DASHBOARD', 'STUDENT', 'SCHEDULE', 'ATTENDANCE', 'TUITION', 'TUITION_ADJUSTMENT',
    'TUITION_RECEIPT', 'FINANCE', 'CONTENT_PROFILE', 'CONTENT_PROGRAM', 'MEDIA',
    'GALLERY', 'BLOG', 'INQUIRY', 'SITE_BRAND', 'COURSE', 'STAFF', 'LESSON_PLAN',
    'LESSON_LOG', 'MAKEUP', 'ENROLLMENT', 'NOTIFICATION', 'CONSENT', 'DATA_TRANSFER',
    'RETENTION', 'ADMIN_ACCOUNT', 'SECURITY_POLICY', 'AUDIT'
  )),
  constraint ck_admin_permission_operation check (operation in (
    'READ', 'WRITE', 'PUBLISH', 'IMPORT', 'EXPORT', 'CLOSE', 'SEND', 'ISSUE', 'EXECUTE'
  ))
);
create index ix_admin_permission_domain_active_code
  on public.admin_permission (domain, active, code);
create table public.admin_role_permission (
  admin_role_id uuid not null references public.admin_role(id) on update restrict on delete restrict,
  admin_permission_id uuid not null references public.admin_permission(id) on update restrict on delete restrict,
  granted_at timestamptz not null default statement_timestamp(),
  primary key (admin_role_id, admin_permission_id)
);
create index ix_admin_role_permission_permission_role
  on public.admin_role_permission (admin_permission_id, admin_role_id);
create table public.admin_user (
  id uuid primary key,
  email varchar(254) not null,
  password_hash varchar(255) not null,
  display_name varchar(100) not null,
  status varchar(20) not null default 'ACTIVE',
  status_reason varchar(200),
  failed_login_count smallint not null default 0 check (failed_login_count between 0 and 10),
  locked_until timestamptz,
  password_must_change boolean not null default false,
  temporary_password_expires_at timestamptz,
  password_changed_at timestamptz not null default statement_timestamp(),
  last_login_at timestamptz,
  created_by uuid,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  version bigint not null default 0 check (version >= 0),
  constraint ck_admin_user_email_normalized check (email = lower(btrim(email))),
  constraint ck_admin_user_display_name check (char_length(btrim(display_name)) between 1 and 100),
  constraint ck_admin_user_status check (status in ('ACTIVE', 'INACTIVE', 'LOCKED')),
  constraint ck_admin_user_status_reason check (
    (status = 'INACTIVE' and status_reason is not null) or
    (status <> 'INACTIVE')
  ),
  constraint ck_admin_user_lock check (
    (status = 'LOCKED' and locked_until is not null) or
    (status <> 'LOCKED' and locked_until is null)
  ),
  constraint ck_admin_user_temporary_password check (
    not password_must_change or temporary_password_expires_at is not null
  )
);
alter table public.admin_user
  add constraint fk_admin_user_created_by
  foreign key (created_by) references public.admin_user(id) on update restrict on delete restrict;
create unique index uq_admin_user_email_lower on public.admin_user (lower(email));
create index ix_admin_user_status_name on public.admin_user (status, display_name, id);
create index ix_admin_user_role_search on public.admin_user (lower(display_name) text_pattern_ops);
create index ix_admin_user_last_login on public.admin_user (last_login_at desc nulls last, id);
create index ix_admin_user_updated on public.admin_user (updated_at desc, id);
create index ix_admin_user_locked_until on public.admin_user (locked_until) where status = 'LOCKED';
create trigger tr_admin_user_updated_at before update on public.admin_user
  for each row execute function public.set_updated_at();
create table public.admin_user_role (
  admin_user_id uuid primary key references public.admin_user(id) on update restrict on delete restrict,
  admin_role_id uuid not null references public.admin_role(id) on update restrict on delete restrict,
  assigned_by uuid references public.admin_user(id) on update restrict on delete restrict,
  assigned_at timestamptz not null default statement_timestamp()
);
create index ix_admin_user_role_role_user on public.admin_user_role (admin_role_id, admin_user_id);
create index ix_admin_user_role_assigned_at on public.admin_user_role (assigned_at desc);
create table public.admin_session_policy (
  id uuid primary key,
  version integer not null unique check (version >= 1),
  max_failed_attempts smallint not null default 5 check (max_failed_attempts between 3 and 10),
  lock_duration_minutes smallint not null default 30 check (lock_duration_minutes between 10 and 120),
  idle_timeout_minutes smallint not null default 60 check (idle_timeout_minutes between 15 and 240),
  absolute_timeout_minutes smallint not null default 720 check (absolute_timeout_minutes between 240 and 1440),
  expiry_warning_minutes smallint not null default 5 check (expiry_warning_minutes between 1 and 10),
  change_reason varchar(300) not null check (char_length(btrim(change_reason)) between 10 and 300),
  effective_from timestamptz not null default statement_timestamp(),
  effective_to timestamptz,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_admin_session_policy_timeouts check (idle_timeout_minutes < absolute_timeout_minutes),
  constraint ck_admin_session_policy_warning check (expiry_warning_minutes < idle_timeout_minutes),
  constraint ck_admin_session_policy_effective_range check (
    effective_to is null or effective_to > effective_from
  )
);
create unique index uq_admin_session_policy_active
  on public.admin_session_policy ((true)) where effective_to is null;
create index ix_admin_session_policy_effective
  on public.admin_session_policy (effective_from desc, version desc);
create table public.admin_session (
  id uuid primary key,
  admin_user_id uuid not null references public.admin_user(id) on update restrict on delete restrict,
  policy_id uuid not null references public.admin_session_policy(id) on update restrict on delete restrict,
  token_hash char(64) not null unique check (token_hash ~ '^[0-9a-f]{64}$'),
  issued_at timestamptz not null default statement_timestamp(),
  last_seen_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  idle_expires_at timestamptz not null,
  revoked_at timestamptz,
  revoke_reason varchar(30),
  ip_address inet,
  user_agent varchar(512),
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_admin_session_expiry check (expires_at > issued_at),
  constraint ck_admin_session_idle_expiry check (
    idle_expires_at > issued_at and idle_expires_at <= expires_at
  ),
  constraint ck_admin_session_revoke_pair check (
    (revoked_at is null and revoke_reason is null) or
    (revoked_at is not null and revoke_reason is not null)
  ),
  constraint ck_admin_session_revoke_time check (revoked_at is null or revoked_at >= issued_at),
  constraint ck_admin_session_revoke_reason check (revoke_reason is null or revoke_reason in (
    'LOGOUT', 'USER_DISABLED', 'PASSWORD_CHANGED', 'PASSWORD_REISSUED', 'ADMIN_REVOKED', 'EXPIRED'
  ))
);
create index ix_admin_session_user_active_expiry
  on public.admin_session (admin_user_id, revoked_at, expires_at);
create index ix_admin_session_idle_active
  on public.admin_session (idle_expires_at) where revoked_at is null;
create index ix_admin_session_absolute_active
  on public.admin_session (expires_at) where revoked_at is null;
