do $$
begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values('rami_consent_evidence_private','rami_consent_evidence_private',false,10485760,
      array['application/pdf','image/jpeg','image/png','image/webp'])
    on conflict(id) do nothing;
    if exists(select 1 from storage.buckets where id='rami_consent_evidence_private' and public) then
      raise exception 'consent evidence storage bucket must remain private';
    end if;
  end if;
end $$;
