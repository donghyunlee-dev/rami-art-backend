set search_path = public, extensions;
alter table public.lesson_plan
  add constraint ck_lesson_plan_change_summary check (
    change_summary is null or char_length(btrim(change_summary)) between 1 and 500
  );
create or replace function public.validate_lesson_plan_item()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  parent_month char(7);
  parent_status varchar(20);
  value jsonb;
  value_text text;
  normalized text[];
begin
  select year_month, status into parent_month, parent_status
    from public.lesson_plan
   where id = new.lesson_plan_id
   for key share;

  if parent_status is distinct from 'DRAFT' then
    raise exception using
      errcode = '55000', constraint = 'ck_lesson_plan_published_immutable',
      message = 'published or archived lesson plan items are immutable';
  end if;
  if to_char(new.planned_date, 'YYYY-MM') <> parent_month then
    raise exception using
      errcode = '23514', constraint = 'ck_lesson_plan_item_parent_month',
      message = 'lesson plan item date must belong to its parent month';
  end if;

  if jsonb_array_length(new.objectives) not between 1 and 5
     or jsonb_array_length(new.activities) not between 1 and 10
     or jsonb_array_length(new.materials) > 20
     or jsonb_array_length(new.preparations) > 20 then
    raise exception using
      errcode = '23514', constraint = 'ck_lesson_plan_item_array_count',
      message = 'lesson plan item array count is invalid';
  end if;

  foreach value in array array[new.objectives, new.activities, new.materials, new.preparations]
  loop
    normalized := array[]::text[];
    for value_text in select jsonb_array_elements_text(value)
    loop
      if char_length(btrim(value_text)) < 1
         or (value = new.objectives and char_length(btrim(value_text)) > 200)
         or (value = new.activities and char_length(btrim(value_text)) > 300)
         or (value in (new.materials, new.preparations) and char_length(btrim(value_text)) > 200) then
        raise exception using
          errcode = '23514', constraint = 'ck_lesson_plan_item_array_value',
          message = 'lesson plan item array value is invalid';
      end if;
      if btrim(value_text) = any(normalized) then
        raise exception using
          errcode = '23514', constraint = 'ck_lesson_plan_item_array_unique',
          message = 'lesson plan item array values must be unique';
      end if;
      normalized := array_append(normalized, btrim(value_text));
    end loop;
  end loop;
  return new;
end;
$$;
create trigger tr_lesson_plan_item_validate
  before insert or update on public.lesson_plan_item
  for each row execute function public.validate_lesson_plan_item();
create or replace function public.prevent_lesson_plan_item_delete()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  parent_status varchar(20);
begin
  select status into parent_status from public.lesson_plan where id = old.lesson_plan_id;
  if parent_status is distinct from 'DRAFT' then
    raise exception using
      errcode = '55000', constraint = 'ck_lesson_plan_published_immutable',
      message = 'published or archived lesson plan items are immutable';
  end if;
  return old;
end;
$$;
create trigger tr_lesson_plan_item_delete_draft_only
  before delete on public.lesson_plan_item
  for each row execute function public.prevent_lesson_plan_item_delete();
create or replace function public.validate_attendance_lesson_plan_link()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  item_date date;
  item_group uuid;
  plan_status varchar(20);
begin
  if new.lesson_plan_item_id is null then
    return new;
  end if;
  select item.planned_date, plan.class_group_id, plan.status
    into item_date, item_group, plan_status
    from public.lesson_plan_item item
    join public.lesson_plan plan on plan.id = item.lesson_plan_id
   where item.id = new.lesson_plan_item_id;
  if item_date is distinct from new.attendance_date
     or item_group is distinct from new.class_group_id
     or plan_status <> 'PUBLISHED' then
    raise exception using
      errcode = '23514', constraint = 'ck_attendance_lesson_plan_link',
      message = 'attendance session and published lesson plan item must match';
  end if;
  return new;
end;
$$;
create trigger tr_attendance_lesson_plan_link
  before insert or update of lesson_plan_item_id, attendance_date, class_group_id
  on public.attendance_session
  for each row execute function public.validate_attendance_lesson_plan_link();
reset search_path;
