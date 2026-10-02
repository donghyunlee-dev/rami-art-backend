set search_path = public, extensions;
create table public.financial_import_batch (
  id uuid primary key,
  file_name varchar(255) not null,
  storage_key varchar(500),
  file_sha256 char(64) not null,
  account_id uuid not null references public.finance_account(id) on update restrict on delete restrict,
  status varchar(20) not null default 'PARSING',
  total_count integer not null default 0,
  valid_count integer not null default 0,
  error_count integer not null default 0,
  duplicate_count integer not null default 0,
  selected_count integer not null default 0,
  imported_count integer not null default 0,
  failed_count integer not null default 0,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  confirmed_at timestamptz,
  expires_at timestamptz not null,
  version bigint not null default 0,
  confirmation_idempotency_scope varchar(300),
  confirmation_idempotency_key uuid,
  confirmation_request_hash char(64),
  constraint ck_financial_import_batch_file_name check (
    file_name=btrim(file_name) and char_length(file_name) between 1 and 255
    and file_name !~ '[\\/]'
  ),
  constraint ck_financial_import_batch_sha check (file_sha256 ~ '^[0-9a-f]{64}$'),
  constraint ck_financial_import_batch_storage check (
    storage_key is null or storage_key ~ '^financial-imports/[0-9a-f-]{36}\\.csv$'
  ),
  constraint ck_financial_import_batch_status check (
    status in ('PARSING','READY','CONFIRMING','CONFIRMED','PARTIAL','FAILED','EXPIRED')
  ),
  constraint ck_financial_import_batch_counts check (
    total_count>=0 and valid_count>=0 and error_count>=0 and duplicate_count>=0
    and selected_count>=0 and imported_count>=0 and failed_count>=0
    and (status in ('PARSING','FAILED','EXPIRED')
      or valid_count+error_count+duplicate_count=total_count)
    and (status not in ('CONFIRMED','PARTIAL')
      or imported_count+failed_count=selected_count)
  ),
  constraint ck_financial_import_batch_confirmation check (
    (status in ('CONFIRMED','PARTIAL') and confirmed_at is not null)
    or (status not in ('CONFIRMED','PARTIAL') and confirmed_at is null)
  ),
  constraint ck_financial_import_batch_expiry check (expires_at>created_at),
  constraint ck_financial_import_batch_version check (version>=0),
  constraint ck_financial_import_batch_confirmation_key check (
    (confirmation_idempotency_scope is null and confirmation_idempotency_key is null
      and confirmation_request_hash is null)
    or (confirmation_idempotency_scope is not null and confirmation_idempotency_key is not null
      and confirmation_request_hash ~ '^[0-9a-f]{64}$')
  )
);
create index ix_financial_import_batch_creator
  on public.financial_import_batch(created_by,created_at desc,id desc);
create index ix_financial_import_batch_expiry
  on public.financial_import_batch(status,expires_at);
create index ix_financial_import_batch_file_warning
  on public.financial_import_batch(file_sha256,account_id,created_at desc);
create unique index uq_financial_import_batch_confirmation_request
  on public.financial_import_batch(confirmation_idempotency_scope,confirmation_idempotency_key)
  where confirmation_idempotency_scope is not null;
create table public.financial_import_row (
  id uuid primary key,
  batch_id uuid not null references public.financial_import_batch(id) on update restrict on delete cascade,
  row_number integer not null,
  transaction_date date,
  type varchar(20),
  amount numeric(14,0),
  description varchar(200),
  category_code varchar(50),
  external_id varchar(100),
  dedup_hash char(64),
  status varchar(20) not null,
  issues jsonb not null default '[]'::jsonb,
  duplicate_entry_id uuid references public.financial_entry(id) on update restrict on delete restrict,
  duplicate_row_id uuid references public.financial_import_row(id) on update restrict on delete restrict,
  financial_entry_id uuid unique references public.financial_entry(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_financial_import_row_number unique(batch_id,row_number),
  constraint ck_financial_import_row_number check (row_number>=2),
  constraint ck_financial_import_row_type check (type is null or type in ('INCOME','EXPENSE')),
  constraint ck_financial_import_row_amount check (amount is null or (amount>0 and scale(amount)=0)),
  constraint ck_financial_import_row_description check (
    description is null or (description=btrim(description) and char_length(description) between 1 and 200)
  ),
  constraint ck_financial_import_row_external check (
    external_id is null or (external_id=btrim(external_id) and char_length(external_id) between 1 and 100)
  ),
  constraint ck_financial_import_row_hash check (
    (external_id is null and (dedup_hash is null or dedup_hash ~ '^[0-9a-f]{64}$'))
    or (external_id is not null and dedup_hash is null)
  ),
  constraint ck_financial_import_row_status check (
    status in ('VALID','ERROR','DUPLICATE','IMPORTED','FAILED','EXCLUDED')
  ),
  constraint ck_financial_import_row_issues check (
    jsonb_typeof(issues)='array' and octet_length(issues::text)<=8192
  ),
  constraint ck_financial_import_row_links check (
    (status='DUPLICATE' and (duplicate_entry_id is null)<>(duplicate_row_id is null)
      and financial_entry_id is null)
    or (status='IMPORTED' and financial_entry_id is not null
      and duplicate_entry_id is null and duplicate_row_id is null)
    or (status not in ('DUPLICATE','IMPORTED') and duplicate_entry_id is null
      and duplicate_row_id is null and financial_entry_id is null)
  )
);
create index ix_financial_import_row_batch_status
  on public.financial_import_row(batch_id,status,row_number,id);
create index ix_financial_import_row_entry
  on public.financial_import_row(financial_entry_id) where financial_entry_id is not null;
create index ix_financial_import_row_hash
  on public.financial_import_row(dedup_hash) where dedup_hash is not null;
create or replace function public.guard_financial_import_batch_source()
returns trigger language plpgsql set search_path='' as $$
begin
  if (new.id,new.file_name,new.file_sha256,new.account_id,new.created_by,new.created_at,new.expires_at)
     is distinct from
     (old.id,old.file_name,old.file_sha256,old.account_id,old.created_by,old.created_at,old.expires_at) then
    raise exception 'financial import batch source is immutable';
  end if;
  if new.version<old.version then raise exception 'financial import batch version cannot decrease'; end if;
  if old.confirmation_idempotency_scope is not null and
     (new.confirmation_idempotency_scope,new.confirmation_idempotency_key,new.confirmation_request_hash)
     is distinct from
     (old.confirmation_idempotency_scope,old.confirmation_idempotency_key,old.confirmation_request_hash) then
    raise exception 'financial import confirmation identity is immutable';
  end if;
  if old.status in ('CONFIRMED','PARTIAL','EXPIRED') and new.status<>old.status then
    raise exception 'terminal financial import batch cannot transition';
  end if;
  return new;
end; $$;
create trigger tr_guard_financial_import_batch_source before update
  on public.financial_import_batch for each row execute function public.guard_financial_import_batch_source();
create or replace function public.guard_financial_import_row_source()
returns trigger language plpgsql set search_path='' as $$
begin
  if (new.id,new.batch_id,new.row_number,new.transaction_date,new.type,new.amount,
      new.description,new.category_code,new.external_id,new.dedup_hash,new.duplicate_row_id,new.created_at)
     is distinct from
     (old.id,old.batch_id,old.row_number,old.transaction_date,old.type,old.amount,
      old.description,old.category_code,old.external_id,old.dedup_hash,old.duplicate_row_id,old.created_at) then
    raise exception 'financial import row source is immutable';
  end if;
  if old.status in ('IMPORTED','FAILED','EXCLUDED') and new.status<>old.status then
    raise exception 'terminal financial import row cannot transition';
  end if;
  return new;
end; $$;
create trigger tr_guard_financial_import_row_source before update
  on public.financial_import_row for each row execute function public.guard_financial_import_row_source();
alter table public.financial_import_batch enable row level security;
alter table public.financial_import_row enable row level security;
create policy financial_import_batch_backend_all on public.financial_import_batch
  for all to rami_backend using (true) with check (true);
create policy financial_import_row_backend_all on public.financial_import_row
  for all to rami_backend using (true) with check (true);
revoke all on table public.financial_import_batch,public.financial_import_row
  from public,anon,authenticated;
grant select,insert,update,delete on public.financial_import_batch,public.financial_import_row
  to rami_backend;
comment on table public.financial_import_batch is 'CSV upload lifecycle and aggregate import result.';
comment on table public.financial_import_row is 'Normalized CSV row validation and immutable ledger import result.';
