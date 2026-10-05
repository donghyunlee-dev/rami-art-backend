do $$
begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values('rami_data_transfers_private','rami_data_transfers_private',false,20971520,array['text/csv'])
    on conflict(id) do nothing;
    if exists(select 1 from storage.buckets where id='rami_data_transfers_private' and public) then
      raise exception 'data transfer storage bucket must remain private';
    end if;
  end if;
end $$;
