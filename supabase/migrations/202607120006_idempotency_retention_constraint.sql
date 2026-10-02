alter table public.idempotency_record
  add constraint ck_idempotency_max_retention
  check (expires_at <= created_at + interval '24 hours');
