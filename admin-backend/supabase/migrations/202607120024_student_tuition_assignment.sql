set search_path = public, extensions;
create table public.student_tuition_assignment (
  id uuid primary key,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  policy_item_id uuid not null
    references public.tuition_policy_item(id) on update restrict on delete restrict,
  effective_from date not null,
  effective_to date,
  override_amount numeric(12,0),
  override_reason varchar(200),
  created_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  updated_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint ck_student_tuition_assignment_period check (
    effective_to is null or effective_to >= effective_from
  ),
  constraint ck_student_tuition_assignment_override check (
    (override_amount is null and override_reason is null)
    or (override_amount between 0 and 999999999999 and scale(override_amount)=0
        and override_reason is not null
        and char_length(btrim(override_reason)) between 5 and 200)
  ),
  constraint ck_student_tuition_assignment_version check (version >= 0),
  constraint ex_student_tuition_assignment_period exclude using gist (
    student_id with =,
    daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') with &&
  )
);
create index ix_student_tuition_assignment_student_period
  on public.student_tuition_assignment (student_id, effective_from, effective_to);
create index ix_student_tuition_assignment_policy_item
  on public.student_tuition_assignment (policy_item_id);
create trigger tr_student_tuition_assignment_updated_at
  before update on public.student_tuition_assignment
  for each row execute function public.set_updated_at();
create table public.tuition_billing_batch (
  id uuid primary key,
  year_month char(7) not null,
  requested_count integer not null,
  created_count integer not null default 0,
  existing_count integer not null default 0,
  failed_count integer not null default 0,
  created_amount numeric(14,0) not null default 0,
  status varchar(20) not null default 'PROCESSING',
  requested_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  started_at timestamptz not null default statement_timestamp(),
  completed_at timestamptz,
  constraint ck_tuition_billing_batch_month check (
    year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
  ),
  constraint ck_tuition_billing_batch_counts check (
    requested_count between 1 and 500 and created_count >= 0
      and existing_count >= 0 and failed_count >= 0
  ),
  constraint ck_tuition_billing_batch_amount check (
    created_amount >= 0 and scale(created_amount)=0
  ),
  constraint ck_tuition_billing_batch_status check (
    status in ('PROCESSING','COMPLETED','PARTIAL','FAILED')
  ),
  constraint ck_tuition_billing_batch_completion check (
    (status='PROCESSING' and completed_at is null)
    or (status<>'PROCESSING' and completed_at is not null
        and created_count+existing_count+failed_count=requested_count)
  )
);
create index ix_tuition_billing_batch_month_started
  on public.tuition_billing_batch (year_month, started_at desc);
create index ix_tuition_billing_batch_requester_started
  on public.tuition_billing_batch (requested_by, started_at desc);
create index ix_tuition_billing_batch_status_started
  on public.tuition_billing_batch (status, started_at);
create table public.tuition_billing (
  id uuid primary key,
  billing_batch_id uuid not null
    references public.tuition_billing_batch(id) on update restrict on delete restrict,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  year_month char(7) not null,
  tuition_assignment_id uuid not null
    references public.student_tuition_assignment(id) on update restrict on delete restrict,
  policy_item_id uuid not null
    references public.tuition_policy_item(id) on update restrict on delete restrict,
  billed_amount numeric(12,0) not null,
  adjustment_amount numeric(12,0) not null default 0,
  paid_amount numeric(12,0) not null default 0,
  refunded_amount numeric(12,0) not null default 0,
  due_date date not null,
  payment_status varchar(20) not null default 'ISSUED',
  issued_by uuid not null references public.admin_user(id) on update restrict on delete restrict,
  issued_at timestamptz not null default statement_timestamp(),
  version bigint not null default 0,
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_tuition_billing_student_month unique (student_id, year_month),
  constraint ck_tuition_billing_month check (
    year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
  ),
  constraint ck_tuition_billing_amounts check (
    billed_amount between 0 and 999999999999 and scale(billed_amount)=0
      and billed_amount+adjustment_amount >= 0 and scale(adjustment_amount)=0
      and paid_amount >= 0 and scale(paid_amount)=0
      and refunded_amount between 0 and paid_amount and scale(refunded_amount)=0
  ),
  constraint ck_tuition_billing_due_month check (
    to_char(due_date, 'YYYY-MM')=year_month
  ),
  constraint ck_tuition_billing_status check (
    payment_status in ('ISSUED','PARTIALLY_PAID','PAID','CREDIT')
  ),
  constraint ck_tuition_billing_version check (version >= 0)
);
create index ix_tuition_billing_month_status_due
  on public.tuition_billing (year_month, payment_status, due_date, id);
create index ix_tuition_billing_student_month
  on public.tuition_billing (student_id, year_month desc);
create index ix_tuition_billing_due_status
  on public.tuition_billing (due_date, payment_status);
create index ix_tuition_billing_assignment
  on public.tuition_billing (tuition_assignment_id, year_month);
create trigger tr_tuition_billing_updated_at before update on public.tuition_billing
  for each row execute function public.set_updated_at();
create or replace function public.protect_billed_tuition_assignment()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' and exists (
    select 1 from public.tuition_billing where tuition_assignment_id=old.id
  ) then
    raise exception using errcode='23514', message='billed tuition assignment is immutable';
  end if;
  if tg_op = 'UPDATE' and exists (
    select 1
      from public.tuition_billing billing
     where billing.tuition_assignment_id=old.id
       and (
         old.policy_item_id is distinct from new.policy_item_id
         or old.override_amount is distinct from new.override_amount
         or not (
           to_date(billing.year_month || '-01', 'YYYY-MM-DD')
             between new.effective_from and coalesce(new.effective_to, 'infinity'::date)
         )
       )
  ) then
    raise exception using errcode='23514', message='billed tuition assignment is immutable';
  end if;
  return case when tg_op='DELETE' then old else new end;
end;
$$;
create trigger tr_student_tuition_assignment_billed_guard
  before update or delete on public.student_tuition_assignment
  for each row execute function public.protect_billed_tuition_assignment();
create or replace function public.protect_tuition_billing_snapshot()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op='DELETE' then
    raise exception using errcode='23514', message='tuition billing is immutable';
  end if;
  if (new.id, new.billing_batch_id, new.student_id, new.year_month,
      new.tuition_assignment_id, new.policy_item_id, new.billed_amount,
      new.due_date, new.issued_by, new.issued_at)
      is distinct from
     (old.id, old.billing_batch_id, old.student_id, old.year_month,
      old.tuition_assignment_id, old.policy_item_id, old.billed_amount,
      old.due_date, old.issued_by, old.issued_at) then
    raise exception using errcode='23514', message='tuition billing snapshot is immutable';
  end if;
  return new;
end;
$$;
create trigger tr_tuition_billing_snapshot_guard
  before update or delete on public.tuition_billing
  for each row execute function public.protect_tuition_billing_snapshot();
reset search_path;
revoke all on table public.student_tuition_assignment,
  public.tuition_billing_batch, public.tuition_billing
from public, anon, authenticated;
grant select, insert, update on public.student_tuition_assignment to rami_backend;
grant select, insert, update on public.tuition_billing_batch to rami_backend;
grant select, insert, update on public.tuition_billing to rami_backend;
alter table public.student_tuition_assignment enable row level security;
alter table public.tuition_billing_batch enable row level security;
alter table public.tuition_billing enable row level security;
create policy student_tuition_assignment_backend_all on public.student_tuition_assignment
  for all to rami_backend using (true) with check (true);
create policy tuition_billing_batch_backend_all on public.tuition_billing_batch
  for all to rami_backend using (true) with check (true);
create policy tuition_billing_backend_all on public.tuition_billing
  for all to rami_backend using (true) with check (true);
