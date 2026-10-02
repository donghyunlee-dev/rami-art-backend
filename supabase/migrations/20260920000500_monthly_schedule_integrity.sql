create unique index if not exists uq_schedule_override_one_item_action
  on public.schedule_override (monthly_schedule_id, schedule_item_id, target_date)
  where schedule_item_id is not null and type in ('CANCEL', 'TIME_CHANGE');

create or replace function public.prevent_published_monthly_schedule_edit()
returns trigger language plpgsql as $$
begin
  if old.status = 'ARCHIVED'
     or (old.status = 'PUBLISHED' and new.status <> 'ARCHIVED') then
    raise exception using errcode = '55000', constraint = 'ck_published_schedule_immutable',
      message = 'published or archived schedule is immutable';
  end if;
  return new;
end;
$$;

create trigger tr_monthly_schedule_immutable
before update on public.monthly_schedule
for each row execute function public.prevent_published_monthly_schedule_edit();
