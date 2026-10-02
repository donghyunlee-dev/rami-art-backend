set search_path = public, extensions;
create or replace function public.require_draft_tuition_policy_item()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  policy_status text;
  policy_id uuid;
begin
  policy_id := case when tg_op = 'DELETE' then old.tuition_policy_id else new.tuition_policy_id end;
  select status into policy_status from public.tuition_policy where id = policy_id;
  if tg_op = 'DELETE' and policy_status is null then
    -- The parent row is no longer visible while its permitted DRAFT delete cascades.
    return old;
  end if;
  if policy_status is distinct from 'DRAFT' then
    raise exception using errcode = '23514', message = 'published tuition policy item is immutable';
  end if;
  return case when tg_op = 'DELETE' then old else new end;
end;
$$;
reset search_path;
