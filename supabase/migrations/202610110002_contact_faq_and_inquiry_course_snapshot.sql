set search_path = public, extensions;

alter table public.studio_profile
  add column faqs jsonb not null default '[]'::jsonb,
  add constraint ck_studio_profile_faqs_json check (jsonb_typeof(faqs) = 'array');

alter table public.inquiry
  add column interested_course_name_snapshot varchar(100);

update public.inquiry i
   set interested_course_name_snapshot = c.name
  from public.course c
 where c.id = i.interested_course_id;

alter table public.inquiry
  add constraint ck_inquiry_course_name_snapshot check (
    interested_course_name_snapshot is null
    or char_length(btrim(interested_course_name_snapshot)) between 1 and 100
  );

reset search_path;
