do $$
begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values('rami_financial_imports_private','rami_financial_imports_private',false,5242880,array['text/csv'])
    on conflict(id) do nothing;
    if exists(select 1 from storage.buckets where id='rami_financial_imports_private' and public) then
      raise exception 'financial import storage bucket must remain private';
    end if;
  end if;
end $$;
