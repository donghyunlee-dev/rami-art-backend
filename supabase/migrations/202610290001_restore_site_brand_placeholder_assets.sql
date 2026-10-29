set search_path = rami_art_studio, extensions;

insert into rami_art_studio.media_asset (
  id, storage_key, public_path, original_file_name, sha256, mime_type,
  file_size, width, height, status, created_by, expires_at
) values
  (md5('seed:generic-brand-logo')::uuid, 'seed/generic-brand-logo.png',
   '/seed/generic-brand-logo.png', 'generic-brand-logo.png',
   encode(digest('generic-brand-logo', 'sha256'), 'hex'), 'image/png',
   1024, 1200, 400, 'READY', null, '9999-12-31 00:00:00+00'),
  (md5('seed:generic-brand-favicon')::uuid, 'seed/generic-brand-favicon.png',
   '/seed/generic-brand-favicon.png', 'generic-brand-favicon.png',
   encode(digest('generic-brand-favicon', 'sha256'), 'hex'), 'image/png',
   512, 512, 512, 'READY', null, '9999-12-31 00:00:00+00'),
  (md5('seed:generic-brand-share')::uuid, 'seed/generic-brand-share.png',
   '/seed/generic-brand-share.png', 'generic-brand-share.png',
   encode(digest('generic-brand-share', 'sha256'), 'hex'), 'image/png',
   2048, 1200, 630, 'READY', null, '9999-12-31 00:00:00+00')
on conflict do nothing;

reset search_path;
