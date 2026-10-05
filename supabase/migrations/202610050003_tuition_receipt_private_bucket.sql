do $$
begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values('rami_tuition_receipts_private','rami_tuition_receipts_private',false,5242880,array['application/pdf'])
    on conflict(id) do nothing;
    if exists(select 1 from storage.buckets where id='rami_tuition_receipts_private' and public) then
      raise exception 'tuition receipt storage bucket must remain private';
    end if;
  end if;
end $$;
