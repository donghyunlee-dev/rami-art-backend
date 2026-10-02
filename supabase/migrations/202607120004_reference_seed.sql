insert into public.admin_role (id, code, name, description, display_order)
values
  (md5('role:OWNER')::uuid, 'OWNER', '원장', '전체 운영과 보안 설정', 1),
  (md5('role:OPERATOR')::uuid, 'OPERATOR', '운영 관리자', '원생·수업·출석·문의 관리', 2),
  (md5('role:CONTENT')::uuid, 'CONTENT', '콘텐츠 관리자', '프로필·수업 소개·갤러리·소식 관리', 3),
  (md5('role:FINANCE')::uuid, 'FINANCE', '재무 관리자', '수업료·입출금·정산 관리', 4)
on conflict (code) do update set
  name = excluded.name,
  description = excluded.description,
  display_order = excluded.display_order,
  active = true;
with permission_codes(code) as (
  select unnest(array[
    'DASHBOARD_READ',
    'STUDENT_READ', 'STUDENT_WRITE', 'STUDENT_STATUS_WRITE', 'STUDENT_NOTE_READ',
    'STUDENT_NOTE_WRITE', 'STUDENT_SCHEDULE_WRITE', 'STUDENT_TUITION_WRITE',
    'SCHEDULE_READ', 'SCHEDULE_WRITE', 'SCHEDULE_PUBLISH',
    'ATTENDANCE_READ', 'ATTENDANCE_WRITE', 'ATTENDANCE_CLOSE',
    'TUITION_POLICY_READ', 'TUITION_POLICY_WRITE', 'TUITION_BILLING_READ',
    'TUITION_BILLING_WRITE', 'TUITION_PAYMENT_READ', 'TUITION_PAYMENT_WRITE',
    'FINANCE_READ', 'FINANCE_WRITE', 'FINANCE_IMPORT',
    'CONTENT_PROFILE_READ', 'CONTENT_PROFILE_WRITE', 'CONTENT_PROGRAM_READ',
    'CONTENT_PROGRAM_WRITE', 'MEDIA_WRITE', 'GALLERY_READ', 'GALLERY_WRITE',
    'GALLERY_PUBLISH', 'BLOG_READ', 'BLOG_WRITE', 'BLOG_PUBLISH',
    'INQUIRY_READ', 'INQUIRY_WRITE', 'ADMIN_ACCOUNT_READ', 'ADMIN_ACCOUNT_WRITE',
    'SECURITY_POLICY_READ', 'SECURITY_POLICY_WRITE', 'AUDIT_READ',
    'SITE_BRAND_READ', 'SITE_BRAND_WRITE', 'SITE_BRAND_PUBLISH',
    'COURSE_READ', 'COURSE_WRITE', 'STAFF_READ', 'STAFF_WRITE',
    'LESSON_PLAN_READ', 'LESSON_PLAN_WRITE', 'LESSON_PLAN_PUBLISH',
    'LESSON_LOG_READ', 'LESSON_LOG_WRITE', 'MAKEUP_READ', 'MAKEUP_WRITE',
    'ENROLLMENT_READ', 'ENROLLMENT_WRITE', 'TUITION_ADJUSTMENT_READ',
    'TUITION_ADJUSTMENT_WRITE', 'TUITION_REFUND_WRITE', 'TUITION_RECEIPT_READ',
    'TUITION_RECEIPT_ISSUE', 'NOTIFICATION_READ', 'NOTIFICATION_SEND',
    'CONSENT_READ', 'CONSENT_WRITE', 'DATA_TRANSFER_IMPORT', 'DATA_TRANSFER_EXPORT',
    'RETENTION_READ', 'RETENTION_EXECUTE'
  ]::text[])
), normalized as (
  select
    code,
    case
      when code like 'TUITION_ADJUSTMENT_%' or code like 'TUITION_REFUND_%' then 'TUITION_ADJUSTMENT'
      when code like 'TUITION_RECEIPT_%' then 'TUITION_RECEIPT'
      when code like 'TUITION_%' then 'TUITION'
      when code like 'STUDENT_%' then 'STUDENT'
      when code like 'CONTENT_PROFILE_%' then 'CONTENT_PROFILE'
      when code like 'CONTENT_PROGRAM_%' then 'CONTENT_PROGRAM'
      when code like 'SITE_BRAND_%' then 'SITE_BRAND'
      when code like 'LESSON_PLAN_%' then 'LESSON_PLAN'
      when code like 'LESSON_LOG_%' then 'LESSON_LOG'
      when code like 'DATA_TRANSFER_%' then 'DATA_TRANSFER'
      when code like 'SECURITY_POLICY_%' then 'SECURITY_POLICY'
      when code like 'ADMIN_ACCOUNT_%' then 'ADMIN_ACCOUNT'
      else regexp_replace(code, '_(READ|WRITE|PUBLISH|IMPORT|EXPORT|CLOSE|SEND|ISSUE|EXECUTE)$', '')
    end as domain,
    substring(code from '(READ|WRITE|PUBLISH|IMPORT|EXPORT|CLOSE|SEND|ISSUE|EXECUTE)$') as operation
  from permission_codes
)
insert into public.admin_permission (id, code, name, domain, operation, description)
select
  md5('permission:' || code)::uuid,
  code,
  code,
  domain,
  operation,
  code || ' 업무 권한'
from normalized
on conflict (code) do update set
  name = excluded.name,
  domain = excluded.domain,
  operation = excluded.operation,
  description = excluded.description,
  active = true;
insert into public.admin_role_permission (admin_role_id, admin_permission_id)
select md5('role:OWNER')::uuid, permission.id
from public.admin_permission permission
where permission.active
on conflict do nothing;
with role_permissions(role_code, permission_codes) as (
  values
    ('OPERATOR', array[
      'DASHBOARD_READ', 'STUDENT_READ', 'STUDENT_WRITE', 'STUDENT_STATUS_WRITE',
      'STUDENT_NOTE_READ', 'STUDENT_NOTE_WRITE', 'STUDENT_SCHEDULE_WRITE',
      'SCHEDULE_READ', 'SCHEDULE_WRITE', 'SCHEDULE_PUBLISH', 'ATTENDANCE_READ',
      'ATTENDANCE_WRITE', 'ATTENDANCE_CLOSE', 'COURSE_READ', 'COURSE_WRITE',
      'STAFF_READ', 'LESSON_PLAN_READ', 'LESSON_PLAN_WRITE', 'LESSON_PLAN_PUBLISH',
      'LESSON_LOG_READ', 'LESSON_LOG_WRITE', 'MAKEUP_READ', 'MAKEUP_WRITE',
      'ENROLLMENT_READ', 'ENROLLMENT_WRITE', 'INQUIRY_READ', 'INQUIRY_WRITE',
      'NOTIFICATION_READ', 'NOTIFICATION_SEND', 'CONSENT_READ', 'CONSENT_WRITE',
      'DATA_TRANSFER_IMPORT'
    ]::text[]),
    ('CONTENT', array[
      'DASHBOARD_READ', 'SITE_BRAND_READ', 'SITE_BRAND_WRITE', 'SITE_BRAND_PUBLISH',
      'CONTENT_PROFILE_READ', 'CONTENT_PROFILE_WRITE', 'CONTENT_PROGRAM_READ',
      'CONTENT_PROGRAM_WRITE', 'MEDIA_WRITE', 'GALLERY_READ', 'GALLERY_WRITE',
      'GALLERY_PUBLISH', 'BLOG_READ', 'BLOG_WRITE', 'BLOG_PUBLISH'
    ]::text[]),
    ('FINANCE', array[
      'DASHBOARD_READ', 'STUDENT_READ', 'STUDENT_TUITION_WRITE',
      'TUITION_POLICY_READ', 'TUITION_POLICY_WRITE', 'TUITION_BILLING_READ',
      'TUITION_BILLING_WRITE', 'TUITION_PAYMENT_READ', 'TUITION_PAYMENT_WRITE',
      'TUITION_ADJUSTMENT_READ', 'TUITION_ADJUSTMENT_WRITE', 'TUITION_REFUND_WRITE',
      'TUITION_RECEIPT_READ', 'TUITION_RECEIPT_ISSUE', 'FINANCE_READ',
      'FINANCE_WRITE', 'FINANCE_IMPORT', 'NOTIFICATION_READ', 'NOTIFICATION_SEND',
      'DATA_TRANSFER_IMPORT', 'DATA_TRANSFER_EXPORT'
    ]::text[])
), expanded as (
  select role_code, unnest(permission_codes) as permission_code from role_permissions
)
insert into public.admin_role_permission (admin_role_id, admin_permission_id)
select role.id, permission.id
from expanded
join public.admin_role role on role.code = expanded.role_code
join public.admin_permission permission on permission.code = expanded.permission_code
on conflict do nothing;
do $$
begin
  if (select count(*) from public.admin_role) <> 4 then
    raise exception 'Expected four fixed admin roles';
  end if;
  if (select count(*) from public.admin_permission where active) <> 70 then
    raise exception 'Expected 70 active admin permissions';
  end if;
  if exists (
    select 1 from public.admin_permission permission
    where permission.active and not exists (
      select 1
      from public.admin_role_permission mapping
      join public.admin_role role on role.id = mapping.admin_role_id and role.code = 'OWNER'
      where mapping.admin_permission_id = permission.id
    )
  ) then
    raise exception 'OWNER must receive every active permission';
  end if;
end;
$$;
