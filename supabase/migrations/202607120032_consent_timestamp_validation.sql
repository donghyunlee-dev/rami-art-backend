-- Application timestamps are captured immediately before the insert statement.
-- Use the wall clock at trigger execution instead of the statement start time so
-- a valid timestamp is not rejected by a few milliseconds of request latency.
create or replace function public.validate_student_consent()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  policy_status varchar(20);
  policy_valid_days integer;
  policy_evidence_required boolean;
  evidence_status varchar(20);
  expected_expiry date;
begin
  select status, valid_days, evidence_required
    into policy_status, policy_valid_days, policy_evidence_required
  from public.consent_policy
  where id = new.consent_policy_id and type = new.policy_type;

  if policy_status is null or policy_status not in ('PUBLISHED', 'ARCHIVED') then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_policy_published',
      message = 'student consent requires a published policy revision';
  end if;

  if new.consented_at > clock_timestamp() then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_not_future',
      message = 'consented_at cannot be in the future';
  end if;

  expected_expiry := case when policy_valid_days is null then null
    else (new.consented_at at time zone 'Asia/Seoul')::date + policy_valid_days end;
  if new.expires_on is distinct from expected_expiry then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_expiry_matches_policy',
      message = 'expires_on must match the policy validity period';
  end if;

  if policy_evidence_required and new.evidence_asset_id is null then
    raise exception using
      errcode = '23514', constraint = 'ck_student_consent_evidence_required',
      message = 'evidence is required';
  end if;

  if new.evidence_asset_id is not null then
    select status into evidence_status from public.media_asset where id = new.evidence_asset_id;
    if evidence_status is distinct from 'READY' then
      raise exception using
        errcode = '23514', constraint = 'ck_student_consent_evidence_ready',
        message = 'evidence asset must be private READY media';
    end if;
  end if;

  return new;
end;
$$;
