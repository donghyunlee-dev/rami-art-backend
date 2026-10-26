set search_path = rami_art_studio, extensions;

drop trigger tr_director_profile_images on director_profile;
drop trigger tr_director_profile_history_guard on director_profile;
drop trigger tr_director_profile_portrait_reference on director_profile;
drop trigger tr_director_facility_image on director_facility;
drop trigger tr_director_facility_draft_only on director_facility;
drop trigger tr_director_facility_media_reference on director_facility;
drop trigger tr_director_about_item_draft_only on director_about_item;

drop function validate_director_profile_images();
drop function validate_director_facility_image();
drop function guard_director_profile_history();
drop function sync_director_profile_media_reference();
drop function sync_director_profile_portrait_reference();
drop function validate_director_image_rights(uuid, text, text, boolean, uuid, boolean, boolean);

alter table media_asset_reference drop constraint ck_director_profile_reference_field;

delete from media_asset_reference reference
using director_facility image, director_profile profile
where reference.owner_type='DIRECTOR_PROFILE'
  and reference.field_name=('facilityImage:' || image.id::text)
  and image.director_profile_id=profile.id
  and profile.status in ('DRAFT','PUBLISHED')
  and not image.visible;

delete from director_facility image
using director_profile profile
where image.director_profile_id=profile.id
  and profile.status in ('DRAFT','PUBLISHED')
  and not image.visible;

delete from director_career career
using director_profile profile
where career.director_profile_id=profile.id
  and profile.status in ('DRAFT','PUBLISHED')
  and career.hidden;

delete from media_asset_reference reference
using director_facility image, director_profile profile
where reference.owner_type='DIRECTOR_PROFILE'
  and reference.field_name=('facilityImage:' || image.id::text)
  and image.director_profile_id=profile.id
  and profile.status='ARCHIVED'
  and (image.media_asset_id is null or image.alt_text is null or char_length(btrim(image.alt_text)) not between 1 and 300
    or char_length(btrim(image.description)) not between 1 and 500);

delete from director_facility image
using director_profile profile
where image.director_profile_id=profile.id
  and profile.status='ARCHIVED'
  and (image.media_asset_id is null or image.alt_text is null or char_length(btrim(image.alt_text)) not between 1 and 300
    or char_length(btrim(image.description)) not between 1 and 500);

drop index ix_director_career_profile_visibility_order;
update director_career set display_order=display_order+10000
where director_profile_id in (select id from director_profile where status in ('DRAFT','PUBLISHED'));
with ordered_careers as (
  select career.id, row_number() over(partition by career.director_profile_id order by career.display_order,career.id)-1 as new_order
  from director_career career join director_profile profile on profile.id=career.director_profile_id
  where profile.status in ('DRAFT','PUBLISHED')
)
update director_career career set display_order=ordered_careers.new_order
from ordered_careers where career.id=ordered_careers.id;

alter table director_facility drop constraint uq_director_facility_order;
alter table director_facility drop constraint ck_director_facility_order;
drop index ix_director_facility_profile_visibility_order;
update director_facility set display_order=display_order+16
where director_profile_id in (select id from director_profile where status in ('DRAFT','PUBLISHED'));
with ordered_images as (
  select image.id, row_number() over(partition by image.director_profile_id order by image.display_order,image.id)-1 as new_order
  from director_facility image join director_profile profile on profile.id=image.director_profile_id
  where profile.status in ('DRAFT','PUBLISHED')
)
update director_facility image set display_order=ordered_images.new_order
from ordered_images where image.id=ordered_images.id;

alter table director_profile
  add column studio_introduction text not null default '',
  drop constraint ck_director_profile_about_lengths,
  drop constraint ck_director_profile_portrait_metadata,
  drop constraint ck_director_profile_lengths,
  drop constraint ck_director_profile_published_fields;

update director_profile set studio_introduction = E'라미아트 미술교습소는 다양한 재료와 주제를 경험하며 스스로 느끼고 생각하고 표현하는 힘을 키우는 미술교습소입니다.\n\n모든 아이들이 가진 고유한 창의성을 존중하고, 그것을 표현할 수 있는 기술과 자신감을 키워주는 것을 목표로 합니다.';

alter table director_profile
  drop column title,
  drop column about_eyebrow,
  drop column about_title,
  drop column about_description,
  drop column philosophy_eyebrow,
  drop column philosophy_title,
  drop column philosophy_description,
  drop column facility_eyebrow,
  drop column facility_title,
  drop column education_eyebrow,
  drop column education_title,
  drop column direction_title,
  drop column direction_description,
  drop column portrait_rights_basis,
  drop column portrait_includes_student,
  drop column portrait_student_consent_id;

alter table director_profile
  add constraint ck_director_profile_lengths check (
    char_length(name) <= 100 and char_length(introduction) <= 1000 and char_length(studio_introduction) <= 2000
  ),
  add constraint ck_director_profile_published_fields check (
    status='DRAFT' or (
      char_length(btrim(name)) between 1 and 100
      and char_length(btrim(introduction)) between 1 and 1000
      and char_length(btrim(studio_introduction)) between 1 and 2000
    )
  ),
  add constraint ck_director_profile_portrait_metadata check (
    (portrait_media_asset_id is null and portrait_alt_text is null)
    or (portrait_media_asset_id is not null and portrait_alt_text is not null
      and char_length(btrim(portrait_alt_text)) between 1 and 300)
  );

drop index ix_director_career_profile_order;
alter table director_career drop column hidden;
create index ix_director_career_profile_order on director_career(director_profile_id,display_order);

drop table director_about_item;

alter table director_facility rename to director_studio_image;
alter table director_studio_image drop constraint ck_director_facility_text;
alter table director_studio_image drop constraint ck_director_facility_visible_complete;
alter table director_studio_image drop constraint ck_director_facility_image_metadata;
alter table director_studio_image drop column name;
alter table director_studio_image drop column rights_basis;
alter table director_studio_image drop column includes_student;
alter table director_studio_image drop column student_consent_id;
alter table director_studio_image drop column visible;
alter table director_studio_image add constraint uq_director_studio_image_order unique(director_profile_id,display_order);
alter table director_studio_image add constraint ck_director_studio_image_order check(display_order between 0 and 11);
alter table director_studio_image add constraint ck_director_studio_image_content check(
  media_asset_id is not null
  and alt_text is not null and char_length(btrim(alt_text)) between 1 and 300
  and char_length(btrim(description)) between 1 and 500
);

update media_asset_reference reference
set field_name='studioImage:' || image.id::text
from director_studio_image image
where reference.owner_type='DIRECTOR_PROFILE'
  and reference.field_name=('facilityImage:' || image.id::text);

alter table media_asset_reference add constraint ck_director_profile_reference_field check (
  owner_type <> 'DIRECTOR_PROFILE' or field_name='portrait' or field_name like 'studioImage:%'
);

create index ix_director_studio_image_profile_order on director_studio_image(director_profile_id,display_order);

create or replace function validate_director_public_image(p_asset_id uuid, p_alt_text text, p_required boolean)
returns void language plpgsql stable set search_path = '' as $$
begin
  if p_asset_id is null then
    if p_required or p_alt_text is not null then
      raise exception using errcode='23514', constraint='ck_director_image_required', message='director image is incomplete';
    end if;
    return;
  end if;
  if not exists(select 1 from rami_art_studio.media_asset where id=p_asset_id and status='READY'
      and public_path is not null and storage_key not like 'private-evidence/%') then
    raise exception using errcode='23514', constraint='ck_director_media_ready', message='director image must be a public READY asset';
  end if;
  if p_alt_text is null or char_length(btrim(p_alt_text)) not between 1 and 300 then
    raise exception using errcode='23514', constraint='ck_director_image_alt_required', message='director image alt text is required';
  end if;
end;
$$;

create or replace function validate_director_profile_images()
returns trigger language plpgsql set search_path = '' as $$
declare studio_image record;
begin
  perform rami_art_studio.validate_director_public_image(new.portrait_media_asset_id,new.portrait_alt_text,false);
  if new.status='PUBLISHED' then
    for studio_image in select media_asset_id,alt_text from rami_art_studio.director_studio_image
      where director_profile_id=new.id order by display_order
    loop
      perform rami_art_studio.validate_director_public_image(studio_image.media_asset_id,studio_image.alt_text,true);
    end loop;
  end if;
  return new;
end;
$$;
create trigger tr_director_profile_images
  before insert or update of status,portrait_media_asset_id,portrait_alt_text on director_profile
  for each row execute function validate_director_profile_images();

create or replace function validate_director_studio_image()
returns trigger language plpgsql set search_path = '' as $$
declare parent_status text;
begin
  select status into parent_status from rami_art_studio.director_profile where id=new.director_profile_id;
  perform rami_art_studio.validate_director_public_image(new.media_asset_id,new.alt_text,true);
  if char_length(btrim(new.description)) not between 1 and 500 then
    raise exception using errcode='23514', constraint='ck_director_studio_image_description', message='studio image description is required';
  end if;
  if parent_status is distinct from 'DRAFT' then
    raise exception using errcode='23514', constraint='ck_director_child_draft_only', message='director studio image may change only in DRAFT';
  end if;
  return new;
end;
$$;
create trigger tr_director_studio_image_validate
  before insert or update on director_studio_image
  for each row execute function validate_director_studio_image();
create trigger tr_director_studio_image_draft_only
  before delete on director_studio_image
  for each row execute function guard_director_child_draft();

create or replace function guard_director_profile_history()
returns trigger language plpgsql set search_path = '' as $$
begin
  if old.status in ('PUBLISHED','ARCHIVED') then
    if old.status='ARCHIVED' or new.status not in ('PUBLISHED','ARCHIVED')
      or new.id is distinct from old.id or new.revision is distinct from old.revision
      or new.based_on_profile_id is distinct from old.based_on_profile_id
      or new.name is distinct from old.name or new.introduction is distinct from old.introduction
      or new.studio_introduction is distinct from old.studio_introduction
      or new.portrait_media_asset_id is distinct from old.portrait_media_asset_id
      or new.portrait_alt_text is distinct from old.portrait_alt_text
      or new.created_by is distinct from old.created_by or new.published_by is distinct from old.published_by
      or new.published_at is distinct from old.published_at then
      raise exception using errcode='23514', constraint='ck_director_profile_immutable', message='published director profile is immutable';
    end if;
  end if;
  return new;
end;
$$;
create trigger tr_director_profile_history_guard before update on director_profile
  for each row execute function guard_director_profile_history();

create or replace function sync_director_studio_image_media_reference()
returns trigger language plpgsql set search_path = '' as $$
begin
  if tg_op='DELETE' then
    delete from rami_art_studio.media_asset_reference where owner_type='DIRECTOR_PROFILE'
      and owner_id=old.director_profile_id and field_name=('studioImage:' || old.id::text)
      and reference_state='DRAFT';
    return old;
  end if;
  delete from rami_art_studio.media_asset_reference where owner_type='DIRECTOR_PROFILE'
    and owner_id=new.director_profile_id and field_name=('studioImage:' || new.id::text)
    and reference_state='DRAFT' and asset_id is distinct from new.media_asset_id;
  if new.media_asset_id is not null then
    insert into rami_art_studio.media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state)
    select new.media_asset_id,'DIRECTOR_PROFILE',new.director_profile_id,'studioImage:' || new.id::text,'DRAFT'
    where (select status from rami_art_studio.director_profile where id=new.director_profile_id)='DRAFT'
    on conflict(asset_id,owner_type,owner_id,field_name) do update set reference_state=excluded.reference_state;
  end if;
  return new;
end;
$$;
create trigger tr_director_studio_image_media_reference after insert or update of media_asset_id or delete
  on director_studio_image for each row execute function sync_director_studio_image_media_reference();

create or replace function sync_director_profile_portrait_reference()
returns trigger language plpgsql set search_path = '' as $$
begin
  delete from rami_art_studio.media_asset_reference where owner_type='DIRECTOR_PROFILE' and owner_id=new.id
    and field_name='portrait' and reference_state='DRAFT' and asset_id is distinct from new.portrait_media_asset_id;
  if new.portrait_media_asset_id is not null then
    insert into rami_art_studio.media_asset_reference(asset_id,owner_type,owner_id,field_name,reference_state)
    values(new.portrait_media_asset_id,'DIRECTOR_PROFILE',new.id,'portrait',
      case when new.status in ('PUBLISHED','ARCHIVED') then 'PUBLISHED' else 'DRAFT' end)
    on conflict(asset_id,owner_type,owner_id,field_name) do update set reference_state=excluded.reference_state;
  end if;
  if new.status in ('PUBLISHED','ARCHIVED') then
    update rami_art_studio.media_asset_reference set reference_state='PUBLISHED'
      where owner_type='DIRECTOR_PROFILE' and owner_id=new.id and reference_state='DRAFT';
  end if;
  return new;
end;
$$;
create trigger tr_director_profile_portrait_reference after insert or update of status,portrait_media_asset_id
  on director_profile for each row execute function sync_director_profile_portrait_reference();

drop policy director_facility_backend_all on director_studio_image;
create policy director_studio_image_backend_all on director_studio_image for all to rami_backend using (true) with check (true);
comment on table director_studio_image is
  'Ordered studio introduction images owned by one director profile revision; all saved images are public on publication.';

reset search_path;
