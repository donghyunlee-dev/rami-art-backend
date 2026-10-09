set search_path = public, extensions;
create table public.consent_evidence_upload (
  asset_id uuid primary key references public.media_asset(id) on delete cascade,
  student_id uuid not null references public.student(id) on delete restrict,
  created_by uuid not null references public.admin_user(id) on delete restrict,
  created_at timestamptz not null default statement_timestamp()
);
create table public.data_transfer_work (
  job_id uuid primary key references public.data_transfer_job(id) on delete restrict,
  payload_ciphertext bytea,
  state varchar(20) not null default 'QUEUED' check(state in ('QUEUED','COMPLETED','FAILED','EXPIRED')),
  attempts integer not null default 0 check(attempts between 0 and 5),
  error_code varchar(80),
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  check((state='QUEUED' and payload_ciphertext is not null) or (state<>'QUEUED' and payload_ciphertext is null))
);
create trigger tr_data_transfer_work_updated before update on public.data_transfer_work
  for each row execute function public.set_updated_at();
create index ix_data_transfer_work_queue on public.data_transfer_work(state,created_at,job_id);
revoke all on public.consent_evidence_upload,public.data_transfer_work from public,anon,authenticated;
grant select,insert,delete on public.consent_evidence_upload to rami_backend;
grant select,insert,update on public.data_transfer_work to rami_backend;
alter table public.consent_evidence_upload enable row level security;
alter table public.data_transfer_work enable row level security;
create policy consent_evidence_upload_backend_all on public.consent_evidence_upload for all to rami_backend using(true) with check(true);
create policy data_transfer_work_backend_all on public.data_transfer_work for all to rami_backend using(true) with check(true);
drop index public.uq_data_transfer_active_file;
create unique index uq_data_transfer_active_file on public.data_transfer_job(direction,domain,sha256)
  where direction='IMPORT' and status not in ('FAILED','EXPIRED');
create or replace function public.protect_confirmed_transfer_row()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.status='CONFIRMED' and not (
    (new.payload_ciphertext is null
      and new.dedup_hash=old.dedup_hash and new.masked_summary=old.masked_summary
      and (to_jsonb(new)-array['payload_ciphertext','updated_at'])=(to_jsonb(old)-array['payload_ciphertext','updated_at']))
    or (new.payload_ciphertext is null and new.dedup_hash=repeat('0',64) and new.masked_summary='EXPIRED'
      and exists(select 1 from public.data_transfer_job j where j.id=old.job_id and j.status='EXPIRED')
      and (to_jsonb(new)-array['payload_ciphertext','dedup_hash','masked_summary','updated_at'])
        =(to_jsonb(old)-array['payload_ciphertext','dedup_hash','masked_summary','updated_at']))
  ) then raise exception using errcode='23514', message='confirmed transfer row is immutable';
  end if;
  return new;
end;
$$;
reset search_path;
