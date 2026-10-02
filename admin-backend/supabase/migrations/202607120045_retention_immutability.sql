set search_path = public, extensions;
create or replace function public.guard_retention_run_history()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.id is distinct from new.id
      or old.domain is distinct from new.domain
      or old.policy_version is distinct from new.policy_version
      or old.cutoff_at is distinct from new.cutoff_at
      or old.preview_version is distinct from new.preview_version
      or old.candidate_count is distinct from new.candidate_count
      or old.hold_excluded_count is distinct from new.hold_excluded_count
      or old.reference_excluded_count is distinct from new.reference_excluded_count
      or old.created_at is distinct from new.created_at
      or new.processed_count < old.processed_count then
    raise exception using errcode='23514', message='retention run evidence is immutable';
  end if;

  if old.status='PREVIEWED' and new.status not in ('PROCESSING','STALE')
      or old.status='PROCESSING' and new.status not in (
        'PROCESSING','COMPLETED','PARTIAL','FAILED')
      or old.status in ('PARTIAL','FAILED') and new.status<>'PROCESSING'
      or old.status in ('COMPLETED','STALE') then
    raise exception using errcode='23514', message='retention run transition is invalid';
  end if;
  return new;
end;
$$;
create trigger tr_retention_run_history_guard
  before update on public.retention_run
  for each row execute function public.guard_retention_run_history();
create or replace function public.guard_private_purge_immutability()
returns trigger language plpgsql set search_path='' as $$
begin
  if tg_table_name='student' and old.private_purged_at is not null
      and new is distinct from old then
    raise exception using errcode='23514', message='purged student private data is immutable';
  elsif tg_table_name='student_consent' and old.evidence_purged_at is not null
      and (new.evidence_asset_id is distinct from old.evidence_asset_id
        or new.evidence_purged_at is distinct from old.evidence_purged_at) then
    raise exception using errcode='23514', message='purged consent evidence is immutable';
  elsif tg_table_name='notification_message' and old.payload_purged_at is not null
      and (new.recipient_ciphertext is distinct from old.recipient_ciphertext
        or new.recipient_hash is distinct from old.recipient_hash
        or new.recipient_last4 is distinct from old.recipient_last4
        or new.subject_ciphertext is distinct from old.subject_ciphertext
        or new.body_ciphertext is distinct from old.body_ciphertext
        or new.variables_ciphertext is distinct from old.variables_ciphertext
        or new.payload_purged_at is distinct from old.payload_purged_at) then
    raise exception using errcode='23514', message='purged notification payload is immutable';
  elsif tg_table_name='notification_batch' and old.payload_purged_at is not null
      and (new.recipient_filter_ciphertext is distinct from old.recipient_filter_ciphertext
        or new.subject_template_ciphertext is distinct from old.subject_template_ciphertext
        or new.body_template_ciphertext is distinct from old.body_template_ciphertext
        or new.variables_ciphertext is distinct from old.variables_ciphertext
        or new.payload_purged_at is distinct from old.payload_purged_at) then
    raise exception using errcode='23514', message='purged notification batch is immutable';
  end if;
  return new;
end;
$$;
create trigger tr_student_private_purge_guard before update on public.student
  for each row execute function public.guard_private_purge_immutability();
create trigger tr_student_consent_evidence_purge_guard before update on public.student_consent
  for each row execute function public.guard_private_purge_immutability();
create trigger tr_notification_message_purge_guard before update on public.notification_message
  for each row execute function public.guard_private_purge_immutability();
create trigger tr_notification_batch_purge_guard before update on public.notification_batch
  for each row execute function public.guard_private_purge_immutability();
reset search_path;
