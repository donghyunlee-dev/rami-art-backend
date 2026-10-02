set search_path = public, extensions;
create table public.data_transfer_job (
  id uuid primary key,
  direction varchar(10) not null,
  domain varchar(20) not null,
  status varchar(20) not null default 'UPLOADED',
  template_version varchar(20),
  source_file_name varchar(255),
  storage_key varchar(500) not null,
  sha256 char(64) not null,
  file_size bigint not null,
  filter_snapshot jsonb,
  purpose varchar(300),
  total_count integer not null default 0,
  valid_count integer not null default 0,
  invalid_count integer not null default 0,
  duplicate_count integer not null default 0,
  confirmed_count integer not null default 0,
  failed_count integer not null default 0,
  cursor_row_number integer not null default 0,
  expires_at timestamptz not null,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  version bigint not null default 0,
  constraint ck_data_transfer_job_direction check (direction in ('IMPORT','EXPORT')),
  constraint ck_data_transfer_job_domain check (domain in ('STUDENT','PAYMENT','ATTENDANCE')),
  constraint ck_data_transfer_job_status check (
    status in ('UPLOADED','PARSING','READY','PROCESSING','COMPLETED','PARTIAL','FAILED','EXPIRED')
  ),
  constraint ck_data_transfer_job_sha check (sha256 ~ '^[0-9a-f]{64}$'),
  constraint ck_data_transfer_job_size check (file_size between 1 and 20971520),
  constraint ck_data_transfer_job_counts check (
    total_count between 0 and 10000
    and valid_count between 0 and total_count
    and invalid_count between 0 and total_count
    and duplicate_count between 0 and total_count
    and confirmed_count between 0 and total_count
    and failed_count between 0 and total_count
    and valid_count + invalid_count + duplicate_count = total_count
    and confirmed_count + failed_count <= valid_count
  ),
  constraint ck_data_transfer_job_cursor check (cursor_row_number between 0 and 10000),
  constraint ck_data_transfer_job_version check (version >= 0),
  constraint ck_data_transfer_job_shape check (
    (direction='IMPORT'
      and source_file_name is not null
      and template_version is not null
      and filter_snapshot is null
      and purpose is null)
    or
    (direction='EXPORT'
      and source_file_name is null
      and template_version is null
      and filter_snapshot is not null
      and purpose is not null
      and char_length(btrim(purpose)) between 5 and 300)
  )
);
create unique index uq_data_transfer_active_file
  on public.data_transfer_job(direction,domain,sha256)
  where status not in ('FAILED','EXPIRED');
create index ix_data_transfer_job_status_created
  on public.data_transfer_job(direction,status,created_at desc,id desc);
create index ix_data_transfer_job_expiry
  on public.data_transfer_job(expires_at,status);
create trigger tr_data_transfer_job_updated_at before update on public.data_transfer_job
  for each row execute function public.set_updated_at();
create table public.data_transfer_row (
  id uuid primary key,
  job_id uuid not null references public.data_transfer_job(id) on update restrict on delete cascade,
  row_number integer not null,
  status varchar(20) not null default 'VALID',
  payload_ciphertext bytea,
  dedup_hash char(64) not null,
  masked_summary varchar(300) not null,
  field_errors jsonb not null default '[]'::jsonb,
  duplicate_target_id uuid,
  result_target_id uuid,
  error_code varchar(80),
  confirmed_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_data_transfer_row_number unique(job_id,row_number),
  constraint ck_data_transfer_row_number check (row_number between 1 and 10000),
  constraint ck_data_transfer_row_status check (
    status in ('VALID','INVALID','DUPLICATE','CONFIRMED','FAILED')
  ),
  constraint ck_data_transfer_row_hash check (dedup_hash ~ '^[0-9a-f]{64}$'),
  constraint ck_data_transfer_row_summary check (
    char_length(btrim(masked_summary)) between 1 and 300
  ),
  constraint ck_data_transfer_row_errors check (jsonb_typeof(field_errors)='array'),
  constraint ck_data_transfer_row_shape check (
    (status='VALID' and payload_ciphertext is not null
      and jsonb_array_length(field_errors)=0
      and duplicate_target_id is null
      and result_target_id is null
      and error_code is null
      and confirmed_at is null)
    or
    (status='INVALID' and payload_ciphertext is not null
      and jsonb_array_length(field_errors)>0
      and duplicate_target_id is null
      and result_target_id is null
      and error_code is null
      and confirmed_at is null)
    or
    (status='DUPLICATE' and payload_ciphertext is not null
      and duplicate_target_id is not null
      and result_target_id is null
      and error_code is null
      and confirmed_at is null)
    or
    (status='CONFIRMED' and result_target_id is not null
      and error_code is null and confirmed_at is not null)
    or
    (status='FAILED' and result_target_id is null
      and error_code is not null and confirmed_at is null)
  )
);
create index ix_data_transfer_row_cursor
  on public.data_transfer_row(job_id,status,row_number,id);
create index ix_data_transfer_row_hash
  on public.data_transfer_row(job_id,dedup_hash,row_number);
create trigger tr_data_transfer_row_updated_at before update on public.data_transfer_row
  for each row execute function public.set_updated_at();
create or replace function public.protect_confirmed_transfer_row()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.status='CONFIRMED' and new is distinct from old then
    raise exception using errcode='23514', message='confirmed transfer row is immutable';
  end if;
  return new;
end;
$$;
create trigger tr_data_transfer_row_confirmed_guard
  before update on public.data_transfer_row
  for each row execute function public.protect_confirmed_transfer_row();
reset search_path;
revoke all on table public.data_transfer_job,public.data_transfer_row
from public,anon,authenticated;
grant select,insert,update on public.data_transfer_job,public.data_transfer_row to rami_backend;
alter table public.data_transfer_job enable row level security;
alter table public.data_transfer_row enable row level security;
create policy data_transfer_job_backend_all on public.data_transfer_job
  for all to rami_backend using(true) with check(true);
create policy data_transfer_row_backend_all on public.data_transfer_row
  for all to rami_backend using(true) with check(true);
