alter table public.attendance_session
  add column room_code_snapshot varchar(30);
update public.attendance_session session
set room_code_snapshot = coalesce(
  (select override.room_code from public.schedule_override override
    where override.id = session.schedule_override_id),
  item.room_code
)
from public.monthly_schedule_item item
where item.id = session.schedule_item_id
  and session.room_code_snapshot is null;
update public.attendance_session session
set room_code_snapshot = override.room_code
from public.schedule_override override
where override.id = session.schedule_override_id
  and session.room_code_snapshot is null;
alter table public.attendance_session
  alter column room_code_snapshot set not null,
  add constraint ck_attendance_session_room_snapshot check (
    room_code_snapshot = upper(btrim(room_code_snapshot))
    and char_length(room_code_snapshot) between 1 and 30
  );
create table public.schedule_publication_result (
  schedule_id uuid primary key references public.monthly_schedule(id) on update restrict on delete restrict,
  generated_from date not null,
  created_session_count integer not null,
  created_target_count integer not null,
  cancelled_session_count integer not null,
  created_at timestamptz not null default statement_timestamp(),
  constraint ck_schedule_publication_result_counts check (
    created_session_count >= 0 and created_target_count >= 0 and cancelled_session_count >= 0
  )
);
revoke all on table public.schedule_publication_result from public, anon, authenticated;
grant select, insert on public.schedule_publication_result to rami_backend;
alter table public.schedule_publication_result enable row level security;
create policy schedule_publication_result_backend_all on public.schedule_publication_result
  for all to rami_backend using (true) with check (true);
