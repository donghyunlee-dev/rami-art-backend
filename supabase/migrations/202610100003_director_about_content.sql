set search_path = public, extensions;

alter table public.director_profile
  add column about_eyebrow varchar(80) not null default '',
  add column about_title varchar(100) not null default '',
  add column about_description varchar(500) not null default '',
  add column philosophy_eyebrow varchar(80) not null default '',
  add column philosophy_title varchar(100) not null default '',
  add column philosophy_description varchar(1000) not null default '',
  add column facility_eyebrow varchar(80) not null default '',
  add column facility_title varchar(100) not null default '',
  add column education_eyebrow varchar(80) not null default '',
  add column education_title varchar(100) not null default '',
  add column direction_title varchar(100) not null default '',
  add column direction_description varchar(1000) not null default '',
  add column portrait_media_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  add column portrait_alt_text varchar(300),
  add column portrait_rights_basis varchar(30),
  add column portrait_includes_student boolean not null default false,
  add column portrait_student_consent_id uuid references public.student_consent(id) on update restrict on delete restrict;

alter table public.director_profile
  add constraint ck_director_profile_about_lengths check (
    char_length(about_eyebrow) <= 80 and char_length(about_title) <= 100 and char_length(about_description) <= 500
    and char_length(philosophy_eyebrow) <= 80 and char_length(philosophy_title) <= 100
    and char_length(philosophy_description) <= 1000 and char_length(facility_eyebrow) <= 80
    and char_length(facility_title) <= 100 and char_length(education_eyebrow) <= 80
    and char_length(education_title) <= 100 and char_length(direction_title) <= 100
    and char_length(direction_description) <= 1000
  ),
  add constraint ck_director_profile_portrait_metadata check (
    (portrait_media_asset_id is null and portrait_alt_text is null and portrait_rights_basis is null
      and not portrait_includes_student and portrait_student_consent_id is null)
    or (portrait_media_asset_id is not null and portrait_alt_text is not null
      and char_length(btrim(portrait_alt_text)) between 1 and 300
      and portrait_rights_basis is not null
      and portrait_rights_basis in ('STUDIO_OWNED','LICENSED','ADULT_RELEASE','STUDENT_CONSENT')
      and ((portrait_includes_student and portrait_rights_basis='STUDENT_CONSENT' and portrait_student_consent_id is not null)
        or (not portrait_includes_student and portrait_rights_basis<>'STUDENT_CONSENT' and portrait_student_consent_id is null)))
  );

create table public.director_about_item (
  id uuid primary key,
  director_profile_id uuid not null references public.director_profile(id) on update restrict on delete restrict,
  item_type varchar(30) not null,
  icon_code varchar(20),
  title varchar(200) not null,
  description varchar(1000),
  display_order smallint not null,
  visible boolean not null default true,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_director_about_item_order unique(director_profile_id,item_type,display_order),
  constraint ck_director_about_item_type check(item_type in ('PHILOSOPHY_VALUE','EDUCATION_VALUE','EDUCATION_DIRECTION')),
  constraint ck_director_about_item_order check(display_order between 0 and 7),
  constraint ck_director_about_item_icon check(
    (item_type='EDUCATION_DIRECTION' and icon_code is null)
    or (item_type in ('PHILOSOPHY_VALUE','EDUCATION_VALUE') and icon_code is not null
      and icon_code in ('LIGHTBULB','HEART','EYE','BRAIN','PALETTE'))
  ),
  constraint ck_director_about_item_content check(
    char_length(btrim(title)) between 1 and 200
    and ((item_type='EDUCATION_DIRECTION' and description is null)
      or (item_type in ('PHILOSOPHY_VALUE','EDUCATION_VALUE') and description is not null and char_length(btrim(description)) between 1 and 1000))
  )
);
create index ix_director_about_item_profile_type_order
  on public.director_about_item(director_profile_id,item_type,visible,display_order);

create table public.director_facility (
  id uuid primary key,
  director_profile_id uuid not null references public.director_profile(id) on update restrict on delete restrict,
  name varchar(100) not null default '',
  description varchar(500) not null default '',
  media_asset_id uuid references public.media_asset(id) on update restrict on delete restrict,
  alt_text varchar(300),
  rights_basis varchar(30),
  includes_student boolean not null default false,
  student_consent_id uuid references public.student_consent(id) on update restrict on delete restrict,
  display_order smallint not null,
  visible boolean not null default false,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_director_facility_order unique(director_profile_id,display_order),
  constraint ck_director_facility_order check(display_order between 0 and 11),
  constraint ck_director_facility_text check(
    char_length(btrim(name)) <= 100 and char_length(btrim(description)) <= 500
    and (alt_text is null or char_length(btrim(alt_text)) between 1 and 300)
  ),
  constraint ck_director_facility_image_metadata check(
    (media_asset_id is null and alt_text is null and rights_basis is null and not includes_student and student_consent_id is null)
    or (media_asset_id is not null and alt_text is not null and char_length(btrim(alt_text)) between 1 and 300
      and rights_basis is not null
      and rights_basis in ('STUDIO_OWNED','LICENSED','ADULT_RELEASE','STUDENT_CONSENT')
      and ((includes_student and rights_basis='STUDENT_CONSENT' and student_consent_id is not null)
        or (not includes_student and rights_basis<>'STUDENT_CONSENT' and student_consent_id is null)))
  ),
  constraint ck_director_facility_visible_complete check(
    not visible or (char_length(btrim(name)) between 1 and 100
      and char_length(btrim(description)) between 1 and 500 and media_asset_id is not null)
  )
);
create index ix_director_facility_profile_visibility_order
  on public.director_facility(director_profile_id,visible,display_order);

alter table public.media_asset_reference add constraint ck_director_profile_reference_field check (
  owner_type <> 'DIRECTOR_PROFILE' or field_name='portrait' or field_name like 'facilityImage:%'
);

create or replace function public.validate_director_image_rights(
  p_asset_id uuid, p_alt_text text, p_rights_basis text, p_includes_student boolean, p_consent_id uuid,
  p_required boolean, p_validate_consent boolean
) returns void language plpgsql stable set search_path = '' as $$
begin
  if p_asset_id is null then
    if p_required or p_alt_text is not null or p_rights_basis is not null or p_includes_student or p_consent_id is not null then
      raise exception using errcode='23514', constraint='ck_director_image_required', message='director image is incomplete';
    end if;
    return;
  end if;
  if not exists(select 1 from public.media_asset where id=p_asset_id and status='READY' and public_path is not null and storage_key not like 'private-evidence/%') then
    raise exception using errcode='23514', constraint='ck_director_media_ready', message='director image must be a public READY asset';
  end if;
  if p_alt_text is null or char_length(btrim(p_alt_text)) not between 1 and 300
    or p_rights_basis is null or p_rights_basis not in ('STUDIO_OWNED','LICENSED','ADULT_RELEASE','STUDENT_CONSENT') then
    raise exception using errcode='23514', constraint='ck_director_image_rights', message='director image alt text and rights basis are required';
  end if;
  if p_includes_student then
    if p_rights_basis<>'STUDENT_CONSENT' or p_consent_id is null then
      raise exception using errcode='23514', constraint='ck_director_student_consent_active', message='director image requires student publication consent';
    end if;
    if p_validate_consent and not exists(
      select 1 from public.student_consent where id=p_consent_id and policy_type='MEDIA_PUBLICATION'
        and status='ACTIVE' and (expires_on is null or expires_on >= (statement_timestamp() at time zone 'Asia/Seoul')::date)
    ) then
      raise exception using errcode='23514', constraint='ck_director_student_consent_active', message='director image requires active student publication consent';
    end if;
  elsif p_rights_basis='STUDENT_CONSENT' or p_consent_id is not null then
    raise exception using errcode='23514', constraint='ck_director_student_consent_unexpected', message='student consent does not match image subjects';
  end if;
end;
$$;

create or replace function public.validate_director_profile_images()
returns trigger language plpgsql set search_path = '' as $$
declare facility_image record;
begin
  if new.status='PUBLISHED' and (
    char_length(btrim(new.about_title))=0 or char_length(btrim(new.about_description))=0
    or char_length(btrim(new.philosophy_title))=0 or char_length(btrim(new.philosophy_description))=0
    or char_length(btrim(new.facility_title))=0 or char_length(btrim(new.education_title))=0
    or char_length(btrim(new.direction_title))=0 or char_length(btrim(new.direction_description))=0
  ) then
    raise exception using errcode='23514', constraint='ck_director_profile_about_published', message='published about content is incomplete';
  end if;
  perform public.validate_director_image_rights(new.portrait_media_asset_id,new.portrait_alt_text,
    new.portrait_rights_basis,new.portrait_includes_student,new.portrait_student_consent_id,false,new.status='PUBLISHED');
  if new.status='PUBLISHED' then
    for facility_image in select media_asset_id,alt_text,rights_basis,includes_student,student_consent_id
      from public.director_facility where director_profile_id=new.id and visible
    loop
      perform public.validate_director_image_rights(facility_image.media_asset_id,facility_image.alt_text,
        facility_image.rights_basis,facility_image.includes_student,facility_image.student_consent_id,true,true);
    end loop;
  end if;
  return new;
end;
$$;
create trigger tr_director_profile_images
  before insert or update of status,portrait_media_asset_id,portrait_alt_text,portrait_rights_basis,
    portrait_includes_student,portrait_student_consent_id on public.director_profile
  for each row execute function public.validate_director_profile_images();

create or replace function public.validate_director_facility_image()
returns trigger language plpgsql set search_path = '' as $$
declare parent_status text;
begin
  select status into parent_status from public.director_profile where id=new.director_profile_id;
  perform public.validate_director_image_rights(new.media_asset_id,new.alt_text,new.rights_basis,
    new.includes_student,new.student_consent_id,new.visible,false);
  if parent_status is distinct from 'DRAFT' then
    raise exception using errcode='23514', constraint='ck_director_child_draft_only', message='director facility may change only in DRAFT';
  end if;
  return new;
end;
$$;
create trigger tr_director_facility_image
  before insert or update on public.director_facility
  for each row execute function public.validate_director_facility_image();

create or replace function public.guard_director_profile_history()
returns trigger language plpgsql set search_path = '' as $$
begin
  if old.status in ('PUBLISHED','ARCHIVED') then
    if old.status='ARCHIVED' or new.status not in ('PUBLISHED','ARCHIVED')
      or new.id is distinct from old.id or new.revision is distinct from old.revision
      or new.based_on_profile_id is distinct from old.based_on_profile_id
      or new.name is distinct from old.name or new.title is distinct from old.title
      or new.introduction is distinct from old.introduction
      or new.about_eyebrow is distinct from old.about_eyebrow or new.about_title is distinct from old.about_title
      or new.about_description is distinct from old.about_description
      or new.philosophy_eyebrow is distinct from old.philosophy_eyebrow or new.philosophy_title is distinct from old.philosophy_title
      or new.philosophy_description is distinct from old.philosophy_description
      or new.facility_eyebrow is distinct from old.facility_eyebrow or new.facility_title is distinct from old.facility_title
      or new.education_eyebrow is distinct from old.education_eyebrow or new.education_title is distinct from old.education_title
      or new.direction_title is distinct from old.direction_title or new.direction_description is distinct from old.direction_description
      or new.portrait_media_asset_id is distinct from old.portrait_media_asset_id
      or new.portrait_alt_text is distinct from old.portrait_alt_text
      or new.portrait_rights_basis is distinct from old.portrait_rights_basis
      or new.portrait_includes_student is distinct from old.portrait_includes_student
      or new.portrait_student_consent_id is distinct from old.portrait_student_consent_id
      or new.created_by is distinct from old.created_by or new.published_by is distinct from old.published_by
      or new.published_at is distinct from old.published_at then
      raise exception using errcode='23514', constraint='ck_director_profile_immutable', message='published director profile is immutable';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_director_profile_history_guard before update on public.director_profile
  for each row execute function public.guard_director_profile_history();

create or replace function public.guard_director_child_draft()
returns trigger language plpgsql set search_path = '' as $$
declare owner_id uuid; owner_status text;
begin
  owner_id := coalesce(new.director_profile_id,old.director_profile_id);
  select status into owner_status from public.director_profile where id=owner_id;
  if owner_status is distinct from 'DRAFT' then
    raise exception using errcode='23514', constraint='ck_director_child_draft_only', message='director child rows may change only in DRAFT';
  end if;
  if tg_op='DELETE' then return old; end if;
  return new;
end;
$$;
create trigger tr_director_career_draft_only before insert or update or delete on public.director_career
  for each row execute function public.guard_director_child_draft();
create trigger tr_director_about_item_draft_only before insert or update or delete on public.director_about_item
  for each row execute function public.guard_director_child_draft();
create trigger tr_director_facility_draft_only before delete on public.director_facility
  for each row execute function public.guard_director_child_draft();

create or replace function public.sync_director_profile_media_reference()
returns trigger language plpgsql set search_path = '' as $$
begin
  if tg_op='DELETE' then
    delete from public.media_asset_reference where owner_type='DIRECTOR_PROFILE' and owner_id=old.director_profile_id
      and field_name=('facilityImage:' || old.id::text) and reference_state='DRAFT';
    return old;
  end if;
  delete from public.media_asset_reference where owner_type='DIRECTOR_PROFILE' and owner_id=new.director_profile_id
    and field_name=('facilityImage:' || new.id::text) and reference_state='DRAFT' and asset_id is distinct from new.media_asset_id;
  if new.media_asset_id is not null then
    insert into public.media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state)
    select new.media_asset_id,'DIRECTOR_PROFILE',new.director_profile_id,'facilityImage:' || new.id::text,'DRAFT'
    where (select status from public.director_profile where id=new.director_profile_id)='DRAFT'
    on conflict(asset_id,owner_type,owner_id,field_name) do update set reference_state=excluded.reference_state;
  end if;
  return new;
end;
$$;
create trigger tr_director_facility_media_reference after insert or update of media_asset_id or delete
  on public.director_facility for each row execute function public.sync_director_profile_media_reference();

create or replace function public.sync_director_profile_portrait_reference()
returns trigger language plpgsql set search_path = '' as $$
begin
  delete from public.media_asset_reference where owner_type='DIRECTOR_PROFILE' and owner_id=new.id
    and field_name='portrait' and reference_state='DRAFT' and asset_id is distinct from new.portrait_media_asset_id;
  if new.portrait_media_asset_id is not null then
    insert into public.media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state)
    values(new.portrait_media_asset_id,'DIRECTOR_PROFILE',new.id,'portrait',
      case when new.status in ('PUBLISHED','ARCHIVED') then 'PUBLISHED' else 'DRAFT' end)
    on conflict(asset_id,owner_type,owner_id,field_name) do update set reference_state=excluded.reference_state;
  end if;
  if new.status in ('PUBLISHED','ARCHIVED') then
    update public.media_asset_reference set reference_state='PUBLISHED'
      where owner_type='DIRECTOR_PROFILE' and owner_id=new.id and reference_state='DRAFT';
    update public.media_asset_reference set reference_state='PUBLISHED'
      where owner_type='DIRECTOR_PROFILE' and owner_id=new.id and field_name like 'facilityImage:%'
        and reference_state='DRAFT';
  end if;
  return new;
end;
$$;
create trigger tr_director_profile_portrait_reference after insert or update of status,portrait_media_asset_id
  on public.director_profile for each row execute function public.sync_director_profile_portrait_reference();

alter table public.director_profile enable row level security;
alter table public.director_about_item enable row level security;
alter table public.director_facility enable row level security;
create policy director_about_item_backend_all on public.director_about_item for all to rami_backend using (true) with check (true);
create policy director_facility_backend_all on public.director_facility for all to rami_backend using (true) with check (true);
revoke all on table public.director_about_item,public.director_facility from public,anon,authenticated;
grant select,insert,update,delete on public.director_about_item,public.director_facility to rami_backend;

reset search_path;
