set search_path = rami_art_studio;

create index ix_media_asset_library_created
  on media_asset (created_at desc, id desc)
  where status = 'READY' and storage_key not like 'private-evidence/%';
