set search_path = public, extensions;
create table public.finance_account (
  id uuid primary key,
  code varchar(50) not null unique,
  name varchar(100) not null,
  type varchar(20) not null check (type in ('BANK','CASH','CARD')),
  currency char(3) not null default 'KRW' check (currency='KRW'),
  active boolean not null default true,
  display_order integer not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_finance_account_display unique (display_order,id),
  constraint ck_finance_account_code check (code ~ '^[A-Z][A-Z0-9_]{2,49}$'),
  constraint ck_finance_account_name check (char_length(btrim(name)) between 1 and 100)
);
create index ix_finance_account_active_order
  on public.finance_account (active,display_order,name);
insert into public.finance_account (id,code,name,type,display_order) values
  ('00000000-0000-7000-8000-000000000101','DEFAULT_BANK','기본 은행','BANK',10),
  ('00000000-0000-7000-8000-000000000102','DEFAULT_CASH','기본 현금','CASH',20),
  ('00000000-0000-7000-8000-000000000103','DEFAULT_CARD','기본 카드','CARD',30);
create table public.finance_category (
  code varchar(50) primary key,
  type varchar(20) not null check (type in ('INCOME','EXPENSE')),
  name varchar(100) not null,
  display_order integer not null default 0,
  active boolean not null default true,
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_finance_category_code_type unique (code,type),
  constraint uq_finance_category_order unique (type,display_order),
  constraint ck_finance_category_code check (code ~ '^[A-Z][A-Z0-9_]{2,49}$'),
  constraint ck_finance_category_name check (char_length(btrim(name)) between 1 and 100)
);
create index ix_finance_category_active_order
  on public.finance_category (type,active,display_order);
insert into public.finance_category (code,type,name,display_order) values
  ('TUITION','INCOME','수업료',10),
  ('OTHER_INCOME','INCOME','기타 수입',20),
  ('MATERIAL','EXPENSE','재료비',10),
  ('RENT','EXPENSE','임대료',20),
  ('UTILITY','EXPENSE','공과금',30),
  ('LABOR','EXPENSE','인건비',40),
  ('TAX','EXPENSE','세금',50),
  ('OTHER_EXPENSE','EXPENSE','기타 지출',60),
  ('TUITION_REFUND','EXPENSE','수업료 환불',70);
create table public.tuition_payment (
  id uuid primary key,
  billing_id uuid not null references public.tuition_billing(id) on update restrict on delete restrict,
  paid_on date not null,
  amount numeric(12,0) not null,
  method varchar(20) not null,
  memo varchar(300),
  status varchar(20) not null default 'CONFIRMED',
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  cancelled_by uuid references public.admin_user(id) on update restrict on delete restrict,
  cancelled_at timestamptz,
  cancel_reason varchar(200),
  version bigint not null default 0,
  constraint ck_tuition_payment_amount check (
    amount between 1 and 999999999999 and scale(amount)=0
  ),
  constraint ck_tuition_payment_method check (method in ('CASH','TRANSFER','CARD','OTHER')),
  constraint ck_tuition_payment_memo check (
    memo is null or (memo=btrim(memo) and char_length(memo) between 1 and 300)
  ),
  constraint ck_tuition_payment_status check (status in ('CONFIRMED','CANCELLED')),
  constraint ck_tuition_payment_cancellation check (
    (status='CONFIRMED' and cancelled_by is null and cancelled_at is null and cancel_reason is null)
    or (status='CANCELLED' and cancelled_by is not null and cancelled_at is not null
        and cancel_reason=btrim(cancel_reason) and char_length(cancel_reason) between 5 and 200)
  ),
  constraint ck_tuition_payment_version check (version >= 0)
);
create index ix_tuition_payment_billing_cursor
  on public.tuition_payment (billing_id,created_at desc,id desc);
create index ix_tuition_payment_paid_status
  on public.tuition_payment (paid_on,status);
create index ix_tuition_payment_status_cancelled
  on public.tuition_payment (status,cancelled_at);
create table public.financial_entry (
  id uuid primary key,
  transaction_date date not null,
  type varchar(20) not null,
  account_id uuid not null references public.finance_account(id) on update restrict on delete restrict,
  category_code varchar(50) not null,
  description varchar(200) not null,
  amount numeric(14,0) not null,
  status varchar(20) not null default 'CONFIRMED',
  source_type varchar(30) not null,
  source_id uuid,
  external_id varchar(100),
  dedup_hash char(64),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  created_at timestamptz not null default statement_timestamp(),
  cancelled_by uuid references public.admin_user(id) on update restrict on delete restrict,
  cancelled_at timestamptz,
  cancel_reason varchar(200),
  version bigint not null default 0,
  constraint fk_financial_entry_category_type foreign key (category_code,type)
    references public.finance_category(code,type) on update restrict on delete restrict,
  constraint ck_financial_entry_amount check (amount>0 and scale(amount)=0),
  constraint ck_financial_entry_type check (type in ('INCOME','EXPENSE')),
  constraint ck_financial_entry_status check (status in ('CONFIRMED','CANCELLED')),
  constraint ck_financial_entry_source check (
    source_type in ('MANUAL','TUITION_PAYMENT','TUITION_REFUND','IMPORT')
    and ((source_type='MANUAL' and source_id is null)
      or (source_type<>'MANUAL' and source_id is not null))
  ),
  constraint ck_financial_entry_import check (
    (source_type<>'IMPORT' and external_id is null and dedup_hash is null)
    or (source_type='IMPORT' and ((external_id is null)<>(dedup_hash is null)))
  ),
  constraint ck_financial_entry_description check (
    description=btrim(description) and char_length(description) between 1 and 200
  ),
  constraint ck_financial_entry_cancellation check (
    (status='CONFIRMED' and cancelled_by is null and cancelled_at is null and cancel_reason is null)
    or (status='CANCELLED' and cancelled_by is not null and cancelled_at is not null
      and cancel_reason=btrim(cancel_reason) and char_length(cancel_reason) between 5 and 200)
  ),
  constraint ck_financial_entry_version check (version>=0)
);
create unique index uq_financial_entry_source
  on public.financial_entry (source_type,source_id) where source_id is not null;
create unique index uq_financial_entry_external
  on public.financial_entry (account_id,external_id) where external_id is not null;
create unique index uq_financial_entry_dedup
  on public.financial_entry (account_id,dedup_hash) where dedup_hash is not null;
create index ix_financial_entry_date on public.financial_entry (transaction_date desc,id desc);
create index ix_financial_entry_account_date
  on public.financial_entry (account_id,transaction_date desc,id desc);
create index ix_financial_entry_category_date
  on public.financial_entry (category_code,transaction_date desc);
create index ix_financial_entry_status_date
  on public.financial_entry (status,transaction_date desc);
create or replace function public.protect_tuition_payment_source()
returns trigger language plpgsql set search_path='' as $$
begin
  if tg_op='DELETE' then
    raise exception using errcode='23514', message='tuition payment is immutable';
  end if;
  if (new.id,new.billing_id,new.paid_on,new.amount,new.method,new.memo,
      new.created_by,new.created_at)
      is distinct from
     (old.id,old.billing_id,old.paid_on,old.amount,old.method,old.memo,
      old.created_by,old.created_at) then
    raise exception using errcode='23514', message='tuition payment source is immutable';
  end if;
  return new;
end;
$$;
create trigger tr_tuition_payment_source_guard before update or delete
  on public.tuition_payment for each row execute function public.protect_tuition_payment_source();
create or replace function public.protect_financial_entry_source()
returns trigger language plpgsql set search_path='' as $$
begin
  if tg_op='DELETE' then
    raise exception using errcode='23514', message='financial entry is immutable';
  end if;
  if (new.id,new.transaction_date,new.type,new.account_id,new.category_code,
      new.description,new.amount,new.source_type,new.source_id,new.external_id,
      new.dedup_hash,new.created_by,new.created_at)
      is distinct from
     (old.id,old.transaction_date,old.type,old.account_id,old.category_code,
      old.description,old.amount,old.source_type,old.source_id,old.external_id,
      old.dedup_hash,old.created_by,old.created_at) then
    raise exception using errcode='23514', message='financial entry source is immutable';
  end if;
  return new;
end;
$$;
create trigger tr_financial_entry_source_guard before update or delete
  on public.financial_entry for each row execute function public.protect_financial_entry_source();
reset search_path;
revoke all on table public.finance_account,public.finance_category,
  public.tuition_payment,public.financial_entry from public,anon,authenticated;
grant select on public.finance_account,public.finance_category to rami_backend;
grant select,insert,update on public.tuition_payment,public.financial_entry to rami_backend;
alter table public.finance_account enable row level security;
alter table public.finance_category enable row level security;
alter table public.tuition_payment enable row level security;
alter table public.financial_entry enable row level security;
create policy finance_account_backend_read on public.finance_account
  for select to rami_backend using (true);
create policy finance_category_backend_read on public.finance_category
  for select to rami_backend using (true);
create policy tuition_payment_backend_all on public.tuition_payment
  for all to rami_backend using (true) with check (true);
create policy financial_entry_backend_all on public.financial_entry
  for all to rami_backend using (true) with check (true);
