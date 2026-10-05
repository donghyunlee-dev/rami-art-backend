create or replace function public.guard_gallery_artwork_history()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.status in ('PUBLISHED','ARCHIVED') then
    if new.artwork_id is distinct from old.artwork_id
      or new.revision is distinct from old.revision
      or (new.visible is distinct from old.visible
        and not (old.status='PUBLISHED' and old.visible and not new.visible))
      or new.based_on_revision_id is distinct from old.based_on_revision_id
      or new.title is distinct from old.title
      or new.course_id is distinct from old.course_id
      or new.audience_label is distinct from old.audience_label
      or new.medium is distinct from old.medium
      or new.description is distinct from old.description
      or new.media_asset_id is distinct from old.media_asset_id
      or new.alt_text is distinct from old.alt_text
      or new.student_consent_id is distinct from old.student_consent_id
      or new.consent_exemption_reason is distinct from old.consent_exemption_reason
      or (new.featured is distinct from old.featured
        and not (old.status='PUBLISHED' and old.visible and not new.visible and not new.featured))
      or (new.featured_order is distinct from old.featured_order
        and not (old.status='PUBLISHED' and old.visible and not new.visible and not new.featured and new.featured_order is null))
      or new.created_by is distinct from old.created_by
      or new.published_by is distinct from old.published_by
      or new.published_at is distinct from old.published_at then
      raise exception using errcode='23514', constraint='ck_gallery_published_immutable',
        message='published gallery artwork content is immutable except consent withdrawal visibility';
    end if;
    if old.status='ARCHIVED' and new.status<>'ARCHIVED' then
      raise exception using errcode='23514', constraint='ck_gallery_archived_terminal',
        message='archived gallery artwork is terminal';
    end if;
  end if;
  return new;
end;
$$;
