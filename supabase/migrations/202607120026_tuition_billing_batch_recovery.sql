set search_path = public, extensions;
alter table public.tuition_billing_batch
  add column idempotency_scope varchar(500),
  add column idempotency_key uuid,
  add column request_hash char(64);
update public.tuition_billing_batch
   set idempotency_scope='legacy:tuition-billing-batch:' || id::text,
       idempotency_key=id,
       request_hash=repeat('0',64)
 where idempotency_scope is null;
alter table public.tuition_billing_batch
  alter column idempotency_scope set not null,
  alter column idempotency_key set not null,
  alter column request_hash set not null,
  add constraint uq_tuition_billing_batch_idempotency
    unique (idempotency_scope,idempotency_key),
  add constraint ck_tuition_billing_batch_request_hash
    check (request_hash ~ '^[0-9a-f]{64}$');
create index ix_tuition_billing_batch_recovery
  on public.tuition_billing_batch (idempotency_scope,idempotency_key,request_hash,status);
reset search_path;
