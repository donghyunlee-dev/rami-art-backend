set search_path = public, extensions;
drop trigger tr_student_private_purge_guard on public.student;
drop trigger tr_student_consent_evidence_purge_guard on public.student_consent;
drop trigger tr_notification_message_purge_guard on public.notification_message;
drop trigger tr_notification_batch_purge_guard on public.notification_batch;
drop function public.guard_private_purge_immutability();
create function public.guard_student_private_purge()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.private_purged_at is not null and new is distinct from old then
    raise exception using errcode='23514', message='purged student private data is immutable';
  end if;
  return new;
end;
$$;
create function public.guard_consent_evidence_purge()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.evidence_purged_at is not null
      and (new.evidence_asset_id is distinct from old.evidence_asset_id
        or new.evidence_purged_at is distinct from old.evidence_purged_at) then
    raise exception using errcode='23514', message='purged consent evidence is immutable';
  end if;
  return new;
end;
$$;
create function public.guard_notification_payload_purge()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.payload_purged_at is not null
      and (new.recipient_ciphertext is distinct from old.recipient_ciphertext
        or new.recipient_hash is distinct from old.recipient_hash
        or new.recipient_last4 is distinct from old.recipient_last4
        or new.subject_ciphertext is distinct from old.subject_ciphertext
        or new.body_ciphertext is distinct from old.body_ciphertext
        or new.variables_ciphertext is distinct from old.variables_ciphertext
        or new.payload_purged_at is distinct from old.payload_purged_at) then
    raise exception using errcode='23514', message='purged notification payload is immutable';
  end if;
  return new;
end;
$$;
create function public.guard_notification_batch_purge()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.payload_purged_at is not null
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
  for each row execute function public.guard_student_private_purge();
create trigger tr_student_consent_evidence_purge_guard before update on public.student_consent
  for each row execute function public.guard_consent_evidence_purge();
create trigger tr_notification_message_purge_guard before update on public.notification_message
  for each row execute function public.guard_notification_payload_purge();
create trigger tr_notification_batch_purge_guard before update on public.notification_batch
  for each row execute function public.guard_notification_batch_purge();
reset search_path;
