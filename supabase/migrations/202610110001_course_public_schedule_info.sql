alter table public.course
  add column session_duration_minutes integer,
  add column weekly_sessions integer;

alter table public.course
  add constraint ck_course_public_schedule_pair check (
    (session_duration_minutes is null and weekly_sessions is null)
    or (session_duration_minutes is not null and weekly_sessions is not null
        and session_duration_minutes between 20 and 240
        and session_duration_minutes % 5 = 0
        and weekly_sessions between 1 and 7)
  );

alter table public.class_program
  add column session_duration_minutes integer,
  add column weekly_sessions integer,
  add column source_course_version bigint not null default 0;

alter table public.class_program
  add constraint ck_class_program_public_schedule_pair check (
    (session_duration_minutes is null and weekly_sessions is null)
    or (session_duration_minutes is not null and weekly_sessions is not null
        and session_duration_minutes between 20 and 240
        and session_duration_minutes % 5 = 0
        and weekly_sessions between 1 and 7)
  );

update public.class_program p
   set audience_label = c.age_guide,
       session_duration_minutes = c.session_duration_minutes,
       weekly_sessions = c.weekly_sessions,
       source_course_version = c.version
  from public.course c
 where c.id = p.course_id;

comment on column public.course.session_duration_minutes is
  'Canonical public course duration in minutes; separate from the class timetable.';
comment on column public.course.weekly_sessions is
  'Canonical number of sessions per week for public course information.';
comment on column public.class_program.source_course_version is
  'Course version captured when the program revision source fields were last synchronized.';
