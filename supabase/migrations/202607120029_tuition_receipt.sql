create sequence public.tuition_receipt_number_seq as bigint start with 1 increment by 1 no cycle;
create table public.tuition_receipt (
  id uuid primary key,
  payment_id uuid not null unique references public.tuition_payment(id) on update restrict on delete restrict,
  receipt_number varchar(20) not null unique,
  current_version integer not null default 1,
  status varchar(20) not null default 'ACTIVE',
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_tuition_receipt_number check (receipt_number ~ '^R-[0-9]{6}-[0-9]{8}$'),
  constraint ck_tuition_receipt_version check (current_version >= 1),
  constraint ck_tuition_receipt_status check (status in ('ACTIVE','VOID'))
);
create table public.tuition_receipt_version (
  id uuid primary key,
  receipt_id uuid not null references public.tuition_receipt(id) on update restrict on delete restrict,
  version integer not null,
  status varchar(20) not null default 'GENERATING',
  snapshot jsonb not null,
  refund_amount numeric(12,0) not null default 0,
  storage_key varchar(500),
  sha256 char(64),
  file_size bigint,
  issue_reason varchar(200),
  issued_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  completed_at timestamptz,
  constraint uq_tuition_receipt_version unique (receipt_id,version),
  constraint ck_tuition_receipt_version_number check (version >= 1),
  constraint ck_tuition_receipt_version_status check (status in ('GENERATING','READY','FAILED')),
  constraint ck_tuition_receipt_snapshot_size check (octet_length(snapshot::text) <= 32768),
  constraint ck_tuition_receipt_refund check (refund_amount >= 0 and refund_amount=trunc(refund_amount)),
  constraint ck_tuition_receipt_issue_reason check (
    (version=1 and issue_reason is null)
    or (version>1 and char_length(btrim(issue_reason)) between 5 and 200)
  ),
  constraint ck_tuition_receipt_file_state check (
    (status='GENERATING' and storage_key is null and sha256 is null and file_size is null and completed_at is null)
    or (status='READY' and storage_key is not null and sha256 ~ '^[0-9a-f]{64}$' and file_size>0 and completed_at is not null)
    or (status='FAILED' and storage_key is null and sha256 is null and file_size is null and completed_at is not null)
  )
);
create index ix_tuition_receipt_created on public.tuition_receipt(created_at desc,id);
create index ix_tuition_receipt_version_created on public.tuition_receipt_version(receipt_id,version desc);
create unique index uq_tuition_receipt_generating on public.tuition_receipt_version(receipt_id) where status='GENERATING';
create trigger tr_tuition_receipt_updated_at before update on public.tuition_receipt
for each row execute function public.set_updated_at();
create or replace function public.guard_tuition_receipt_source()
returns trigger language plpgsql set search_path='' as $$
begin
  if new.id<>old.id or new.payment_id<>old.payment_id or new.receipt_number<>old.receipt_number
     or new.created_by<>old.created_by or new.created_at<>old.created_at then
    raise exception 'tuition receipt source is immutable';
  end if;
  if new.current_version<old.current_version then raise exception 'receipt version cannot decrease'; end if;
  if old.status='VOID' and new.status<>'VOID' then raise exception 'void receipt cannot reactivate'; end if;
  return new;
end; $$;
create trigger tr_guard_tuition_receipt_source before update on public.tuition_receipt
for each row execute function public.guard_tuition_receipt_source();
create or replace function public.guard_tuition_receipt_version()
returns trigger language plpgsql set search_path='' as $$
begin
  if new.id<>old.id or new.receipt_id<>old.receipt_id or new.version<>old.version
     or new.snapshot<>old.snapshot or new.refund_amount<>old.refund_amount
     or new.issue_reason is distinct from old.issue_reason or new.issued_by<>old.issued_by
     or new.created_at<>old.created_at then
    raise exception 'tuition receipt version source is immutable';
  end if;
  if old.status<>'GENERATING' or new.status not in ('READY','FAILED') then
    raise exception 'invalid tuition receipt version transition';
  end if;
  return new;
end; $$;
create trigger tr_guard_tuition_receipt_version before update on public.tuition_receipt_version
for each row execute function public.guard_tuition_receipt_version();
alter table public.tuition_receipt enable row level security;
alter table public.tuition_receipt_version enable row level security;
create policy tuition_receipt_backend_all on public.tuition_receipt for all to rami_backend using (true) with check (true);
create policy tuition_receipt_version_backend_all on public.tuition_receipt_version for all to rami_backend using (true) with check (true);
grant select,insert,update on public.tuition_receipt to rami_backend;
grant select,insert,update on public.tuition_receipt_version to rami_backend;
grant usage,select on sequence public.tuition_receipt_number_seq to rami_backend;
comment on table public.tuition_receipt is 'Stable receipt header for a confirmed tuition payment.';
comment on table public.tuition_receipt_version is 'Immutable receipt issue snapshots and PDF integrity metadata.';
