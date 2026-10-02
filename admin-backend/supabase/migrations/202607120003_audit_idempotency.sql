create table public.audit_log (
  id uuid primary key,
  occurred_at timestamptz not null default statement_timestamp(),
  request_id varchar(100) not null,
  task_id varchar(100) not null check (task_id ~ '^MGT-[A-Z0-9-]+$'),
  event_class varchar(20) not null check (event_class in ('SECURITY', 'PRIVILEGE', 'FINANCE', 'OPERATION')),
  actor_type varchar(20) not null check (actor_type in ('ADMIN', 'SYSTEM', 'ANONYMOUS')),
  actor_id uuid,
  actor_display varchar(120),
  action varchar(100) not null check (action ~ '^[A-Z][A-Z0-9_]{2,99}$'),
  target_type varchar(100),
  target_id uuid,
  target_display varchar(200),
  result varchar(20) not null check (result in ('SUCCESS', 'FAILURE')),
  reason_code varchar(100),
  ip_address inet,
  user_agent varchar(512),
  details jsonb not null default '{}'::jsonb,
  constraint ck_audit_actor check (
    (actor_type = 'ADMIN' and actor_id is not null) or
    (actor_type <> 'ADMIN' and actor_id is null)
  ),
  constraint ck_audit_target check (
    (target_type is null and target_id is null) or target_type is not null
  ),
  constraint ck_audit_details_object check (jsonb_typeof(details) = 'object'),
  constraint ck_audit_details_size check (pg_column_size(details) <= 16384)
);
create index ix_audit_log_cursor on public.audit_log (occurred_at desc, id desc);
create index ix_audit_log_actor on public.audit_log (actor_id, occurred_at desc, id desc)
  where actor_id is not null;
create index ix_audit_log_task on public.audit_log (task_id, occurred_at desc, id desc);
create index ix_audit_log_action on public.audit_log (action, occurred_at desc, id desc);
create index ix_audit_log_result on public.audit_log (result, occurred_at desc, id desc);
create index ix_audit_log_target on public.audit_log (target_type, target_id, occurred_at desc)
  where target_type is not null;
create table public.idempotency_record (
  scope varchar(100) not null,
  idempotency_key uuid not null,
  request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
  response_status smallint,
  encrypted_response bytea,
  sensitive_response_revealed_at timestamptz,
  resource_id uuid,
  state varchar(20) not null default 'PROCESSING'
    check (state in ('PROCESSING', 'COMPLETED', 'FAILED')),
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null,
  primary key (scope, idempotency_key),
  constraint ck_idempotency_completed_response check (
    state = 'PROCESSING' or response_status is not null
  ),
  constraint ck_idempotency_expiry check (expires_at > created_at)
);
create index ix_idempotency_expiry on public.idempotency_record (expires_at);
create index ix_idempotency_resource on public.idempotency_record (resource_id)
  where resource_id is not null;
create trigger tr_idempotency_updated_at before update on public.idempotency_record
  for each row execute function public.set_updated_at();
