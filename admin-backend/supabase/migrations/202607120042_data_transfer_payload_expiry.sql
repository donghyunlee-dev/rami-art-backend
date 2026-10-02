set search_path = public, extensions;
create or replace function public.protect_confirmed_transfer_row()
returns trigger language plpgsql set search_path='' as $$
begin
  if old.status='CONFIRMED' and not (
    new.id=old.id
    and new.job_id=old.job_id
    and new.row_number=old.row_number
    and new.status=old.status
    and new.payload_ciphertext is null
    and new.dedup_hash=old.dedup_hash
    and new.masked_summary=old.masked_summary
    and new.field_errors=old.field_errors
    and new.duplicate_target_id is not distinct from old.duplicate_target_id
    and new.result_target_id is not distinct from old.result_target_id
    and new.error_code is not distinct from old.error_code
    and new.confirmed_at is not distinct from old.confirmed_at
    and new.created_at=old.created_at
  ) then
    raise exception using errcode='23514', message='confirmed transfer row is immutable';
  end if;
  return new;
end;
$$;
reset search_path;
