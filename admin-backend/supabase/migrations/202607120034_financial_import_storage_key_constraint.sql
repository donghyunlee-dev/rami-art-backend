alter table public.financial_import_batch
  drop constraint ck_financial_import_batch_storage;
alter table public.financial_import_batch
  add constraint ck_financial_import_batch_storage check (
    storage_key is null
    or storage_key ~ '^financial-imports/[0-9a-f-]{36}\.csv$'
  );
