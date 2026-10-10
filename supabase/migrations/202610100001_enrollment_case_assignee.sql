alter table public.enrollment_case
  add column assignee_admin_user_id uuid;

update public.enrollment_case
set assignee_admin_user_id = created_by;

alter table public.enrollment_case
  alter column assignee_admin_user_id set not null,
  add constraint fk_enrollment_case_assignee
    foreign key (assignee_admin_user_id) references public.admin_user(id)
    on update restrict on delete restrict;

create index ix_enrollment_case_assignee_status_updated
  on public.enrollment_case (assignee_admin_user_id, status, updated_at desc, id);
