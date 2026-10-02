alter table public.media_asset drop constraint ck_media_asset_status;
alter table public.media_asset
  add constraint ck_media_asset_status check (status = 'READY');

