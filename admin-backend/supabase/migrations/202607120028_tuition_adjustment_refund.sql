set search_path = public, extensions;
create table public.billing_adjustment (
  id uuid primary key,
  billing_id uuid not null references public.tuition_billing(id) on update restrict on delete restrict,
  type varchar(20) not null,
  signed_amount numeric(12,0) not null,
  reason varchar(300) not null,
  status varchar(20) not null default 'CONFIRMED',
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  cancelled_by uuid references public.admin_user(id) on update restrict on delete restrict,
  cancelled_at timestamptz,
  cancel_reason varchar(200),
  version bigint not null default 0,
  constraint ck_billing_adjustment_type check (
    type in ('DISCOUNT','MATERIAL','EXTRA','CORRECTION')
  ),
  constraint ck_billing_adjustment_amount check (
    signed_amount between -999999999999 and 999999999999
      and signed_amount<>0 and scale(signed_amount)=0
      and ((type='DISCOUNT' and signed_amount<0)
        or (type in ('MATERIAL','EXTRA') and signed_amount>0)
        or type='CORRECTION')
  ),
  constraint ck_billing_adjustment_reason check (
    reason=btrim(reason) and char_length(reason) between 5 and 300
  ),
  constraint ck_billing_adjustment_status check (status in ('CONFIRMED','CANCELLED')),
  constraint ck_billing_adjustment_cancellation check (
    (status='CONFIRMED' and cancelled_by is null and cancelled_at is null and cancel_reason is null)
    or (status='CANCELLED' and cancelled_by is not null and cancelled_at is not null
      and cancel_reason=btrim(cancel_reason) and char_length(cancel_reason) between 5 and 200)
  ),
  constraint ck_billing_adjustment_version check (version>=0)
);
create index ix_billing_adjustment_billing_status
  on public.billing_adjustment (billing_id,status,created_at,id);
create index ix_billing_adjustment_type_created
  on public.billing_adjustment (type,created_at desc);
create table public.tuition_refund (
  id uuid primary key,
  billing_id uuid not null references public.tuition_billing(id) on update restrict on delete restrict,
  payment_id uuid references public.tuition_payment(id) on update restrict on delete restrict,
  refunded_on date not null,
  amount numeric(12,0) not null,
  method varchar(20) not null,
  reason varchar(300) not null,
  status varchar(20) not null default 'CONFIRMED',
  financial_entry_id uuid not null unique
    references public.financial_entry(id) on update restrict on delete restrict,
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  cancelled_by uuid references public.admin_user(id) on update restrict on delete restrict,
  cancelled_at timestamptz,
  cancel_reason varchar(200),
  version bigint not null default 0,
  constraint ck_tuition_refund_amount check (
    amount between 1 and 999999999999 and scale(amount)=0
  ),
  constraint ck_tuition_refund_method check (method in ('CASH','TRANSFER','CARD','OTHER')),
  constraint ck_tuition_refund_reason check (
    reason=btrim(reason) and char_length(reason) between 5 and 300
  ),
  constraint ck_tuition_refund_status check (status in ('CONFIRMED','CANCELLED')),
  constraint ck_tuition_refund_cancellation check (
    (status='CONFIRMED' and cancelled_by is null and cancelled_at is null and cancel_reason is null)
    or (status='CANCELLED' and cancelled_by is not null and cancelled_at is not null
      and cancel_reason=btrim(cancel_reason) and char_length(cancel_reason) between 5 and 200)
  ),
  constraint ck_tuition_refund_version check (version>=0)
);
create index ix_tuition_refund_billing_status
  on public.tuition_refund (billing_id,status,refunded_on,id);
create index ix_tuition_refund_payment_status
  on public.tuition_refund (payment_id,status);
create or replace function public.protect_billing_adjustment_source()
returns trigger language plpgsql set search_path='' as $$
begin
  if tg_op='DELETE' then
    raise exception using errcode='23514',message='billing adjustment is immutable';
  end if;
  if (new.id,new.billing_id,new.type,new.signed_amount,new.reason,new.created_by,new.created_at)
      is distinct from
     (old.id,old.billing_id,old.type,old.signed_amount,old.reason,old.created_by,old.created_at) then
    raise exception using errcode='23514',message='billing adjustment source is immutable';
  end if;
  return new;
end;
$$;
create trigger tr_billing_adjustment_source_guard before update or delete
  on public.billing_adjustment for each row execute function public.protect_billing_adjustment_source();
create or replace function public.protect_tuition_refund_source()
returns trigger language plpgsql set search_path='' as $$
begin
  if tg_op='DELETE' then
    raise exception using errcode='23514',message='tuition refund is immutable';
  end if;
  if (new.id,new.billing_id,new.payment_id,new.refunded_on,new.amount,new.method,
      new.reason,new.financial_entry_id,new.created_by,new.created_at)
      is distinct from
     (old.id,old.billing_id,old.payment_id,old.refunded_on,old.amount,old.method,
      old.reason,old.financial_entry_id,old.created_by,old.created_at) then
    raise exception using errcode='23514',message='tuition refund source is immutable';
  end if;
  return new;
end;
$$;
create trigger tr_tuition_refund_source_guard before update or delete
  on public.tuition_refund for each row execute function public.protect_tuition_refund_source();
reset search_path;
revoke all on table public.billing_adjustment,public.tuition_refund
  from public,anon,authenticated;
grant select,insert,update on public.billing_adjustment,public.tuition_refund to rami_backend;
alter table public.billing_adjustment enable row level security;
alter table public.tuition_refund enable row level security;
create policy billing_adjustment_backend_all on public.billing_adjustment
  for all to rami_backend using (true) with check (true);
create policy tuition_refund_backend_all on public.tuition_refund
  for all to rami_backend using (true) with check (true);
