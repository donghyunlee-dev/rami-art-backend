set search_path = public, extensions;
alter table public.tuition_billing
  add column assignment_version bigint,
  add column override_amount_snapshot numeric(12,0),
  add column override_reason_snapshot varchar(200);
update public.tuition_billing billing
   set assignment_version = assignment.version,
       override_amount_snapshot = assignment.override_amount,
       override_reason_snapshot = assignment.override_reason
  from public.student_tuition_assignment assignment
 where assignment.id = billing.tuition_assignment_id;
alter table public.tuition_billing
  alter column assignment_version set not null,
  add constraint ck_tuition_billing_assignment_version check (assignment_version >= 0),
  add constraint ck_tuition_billing_override_snapshot check (
    (override_amount_snapshot is null and override_reason_snapshot is null)
    or (override_amount_snapshot between 0 and 999999999999
        and scale(override_amount_snapshot)=0
        and override_reason_snapshot is not null
        and char_length(btrim(override_reason_snapshot)) between 5 and 200)
  ),
  add constraint ck_tuition_billing_payment_shape check (
    (payment_status='ISSUED'
      and billed_amount+adjustment_amount > 0
      and paid_amount-refunded_amount = 0)
    or (payment_status='PARTIALLY_PAID'
      and paid_amount-refunded_amount > 0
      and paid_amount-refunded_amount < billed_amount+adjustment_amount)
    or (payment_status='PAID'
      and paid_amount-refunded_amount = billed_amount+adjustment_amount)
    or (payment_status='CREDIT'
      and paid_amount-refunded_amount > billed_amount+adjustment_amount)
  );
create table public.tuition_billing_batch_result (
  id uuid primary key,
  billing_batch_id uuid not null
    references public.tuition_billing_batch(id) on update restrict on delete restrict,
  student_id uuid not null references public.student(id) on update restrict on delete restrict,
  result_status varchar(20) not null,
  billing_id uuid references public.tuition_billing(id) on update restrict on delete restrict,
  amount numeric(12,0),
  error_code varchar(80),
  created_at timestamptz not null default statement_timestamp(),
  constraint uq_tuition_billing_batch_result_student unique (billing_batch_id, student_id),
  constraint ck_tuition_billing_batch_result_status check (
    result_status in ('CREATED','EXISTING','FAILED')
  ),
  constraint ck_tuition_billing_batch_result_shape check (
    (result_status in ('CREATED','EXISTING')
      and billing_id is not null and amount is not null and amount >= 0
      and scale(amount)=0 and error_code is null)
    or (result_status='FAILED'
      and billing_id is null and amount is null and error_code is not null
      and char_length(error_code) between 1 and 80)
  )
);
create index ix_tuition_billing_batch_result_batch_status
  on public.tuition_billing_batch_result (billing_batch_id, result_status, student_id);
create index ix_tuition_billing_batch_result_billing
  on public.tuition_billing_batch_result (billing_id) where billing_id is not null;
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
      new.due_date, new.issued_by, new.issued_at, new.assignment_version,
      new.override_amount_snapshot, new.override_reason_snapshot)
      is distinct from
     (old.id, old.billing_batch_id, old.student_id, old.year_month,
      old.tuition_assignment_id, old.policy_item_id, old.billed_amount,
      old.due_date, old.issued_by, old.issued_at, old.assignment_version,
      old.override_amount_snapshot, old.override_reason_snapshot) then
    raise exception using errcode='23514', message='tuition billing snapshot is immutable';
  end if;
  return new;
end;
$$;
reset search_path;
revoke all on table public.tuition_billing_batch_result from public, anon, authenticated;
grant select, insert on public.tuition_billing_batch_result to rami_backend;
alter table public.tuition_billing_batch_result enable row level security;
create policy tuition_billing_batch_result_backend_all
  on public.tuition_billing_batch_result for all to rami_backend
  using (true) with check (true);
