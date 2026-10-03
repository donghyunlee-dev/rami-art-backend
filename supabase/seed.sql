-- Local development bootstrap only. Change the temporary password immediately.
insert into public.admin_role (id, code, name, description, display_order)
values
    ('10000000-0000-0000-0000-000000000001', 'OWNER', '원장', '전체 운영과 보안 설정', 1),
    ('10000000-0000-0000-0000-000000000002', 'OPERATOR', '운영 관리자', '원생·수업·출석·문의 관리', 2),
    ('10000000-0000-0000-0000-000000000003', 'CONTENT', '콘텐츠 관리자', '프로필·수업 소개·갤러리·소식 관리', 3),
    ('10000000-0000-0000-0000-000000000004', 'FINANCE', '재무 관리자', '수업료·입출금·정산 관리', 4)
on conflict (code) do update set
    name = excluded.name,
    description = excluded.description,
    display_order = excluded.display_order,
    active = true;

insert into public.admin_permission (code, name, domain, operation, description)
values
    ('DASHBOARD_READ', '대시보드 조회', 'DASHBOARD', 'READ', '관리자 대시보드 조회'),
    ('STUDENT_READ', '원생 조회', 'STUDENT', 'READ', '원생 기본 정보 조회'),
    ('STUDENT_WRITE', '원생 편집', 'STUDENT', 'WRITE', '원생 기본 정보 생성 및 변경'),
    ('STUDENT_STATUS_WRITE', '원생 상태 변경', 'STUDENT', 'WRITE', '원생 재원 상태 변경'),
    ('STUDENT_NOTE_READ', '원생 메모 조회', 'STUDENT', 'READ', '원생 운영 메모 조회'),
    ('STUDENT_NOTE_WRITE', '원생 메모 편집', 'STUDENT', 'WRITE', '원생 운영 메모 작성 및 변경'),
    ('STUDENT_SCHEDULE_WRITE', '원생 일정 변경', 'STUDENT', 'WRITE', '원생 수업 일정 변경'),
    ('STUDENT_TUITION_WRITE', '원생 수업료 변경', 'STUDENT', 'WRITE', '원생별 수업료 정보 변경'),
    ('SCHEDULE_READ', '시간표 조회', 'SCHEDULE', 'READ', '수업 시간표 조회'),
    ('SCHEDULE_WRITE', '시간표 편집', 'SCHEDULE', 'WRITE', '수업 시간표 작성 및 변경'),
    ('SCHEDULE_PUBLISH', '시간표 발행', 'SCHEDULE', 'PUBLISH', '수업 시간표 발행'),
    ('ATTENDANCE_READ', '출결 조회', 'ATTENDANCE', 'READ', '출석 및 결석 정보 조회'),
    ('ATTENDANCE_WRITE', '출결 편집', 'ATTENDANCE', 'WRITE', '출석 및 결석 정보 변경'),
    ('ATTENDANCE_CLOSE', '출결 마감', 'ATTENDANCE', 'CLOSE', '일별 출결 마감'),
    ('TUITION_POLICY_READ', '수업료 정책 조회', 'TUITION', 'READ', '수업료 정책 조회'),
    ('TUITION_POLICY_WRITE', '수업료 정책 편집', 'TUITION', 'WRITE', '수업료 정책 작성 및 변경'),
    ('TUITION_BILLING_READ', '청구 조회', 'TUITION', 'READ', '수업료 청구 조회'),
    ('TUITION_BILLING_WRITE', '청구 편집', 'TUITION', 'WRITE', '수업료 청구 생성 및 변경'),
    ('TUITION_PAYMENT_READ', '납부 조회', 'TUITION', 'READ', '수업료 납부 조회'),
    ('TUITION_PAYMENT_WRITE', '납부 편집', 'TUITION', 'WRITE', '수업료 납부 등록 및 변경'),
    ('TUITION_ADJUSTMENT_READ', '조정 조회', 'TUITION', 'READ', '수업료 조정 내역 조회'),
    ('TUITION_ADJUSTMENT_WRITE', '조정 편집', 'TUITION', 'WRITE', '수업료 조정 생성 및 변경'),
    ('TUITION_REFUND_WRITE', '환불 처리', 'TUITION', 'WRITE', '수업료 환불 등록'),
    ('TUITION_RECEIPT_READ', '영수증 조회', 'TUITION', 'READ', '수업료 영수증 조회'),
    ('TUITION_RECEIPT_ISSUE', '영수증 발급', 'TUITION', 'ISSUE', '수업료 영수증 발급'),
    ('FINANCE_READ', '재무 조회', 'FINANCE', 'READ', '입출금 및 정산 조회'),
    ('FINANCE_WRITE', '재무 편집', 'FINANCE', 'WRITE', '입출금 및 정산 변경'),
    ('FINANCE_IMPORT', '재무 가져오기', 'FINANCE', 'IMPORT', '재무 자료 가져오기'),
    ('CONTENT_PROFILE_READ', '프로필 조회', 'CONTENT_PROFILE', 'READ', '원장 프로필 콘텐츠 조회'),
    ('CONTENT_PROFILE_WRITE', '프로필 편집', 'CONTENT_PROFILE', 'WRITE', '원장 프로필 콘텐츠 변경'),
    ('CONTENT_PROGRAM_READ', '수업 소개 조회', 'CONTENT_PROGRAM', 'READ', '수업 소개 콘텐츠 조회'),
    ('CONTENT_PROGRAM_WRITE', '수업 소개 편집', 'CONTENT_PROGRAM', 'WRITE', '수업 소개 콘텐츠 변경'),
    ('MEDIA_WRITE', '미디어 편집', 'MEDIA', 'WRITE', '이미지와 파일 업로드 및 변경'),
    ('GALLERY_READ', '갤러리 조회', 'GALLERY', 'READ', '갤러리 콘텐츠 조회'),
    ('GALLERY_WRITE', '갤러리 편집', 'GALLERY', 'WRITE', '갤러리 콘텐츠 작성 및 변경'),
    ('GALLERY_PUBLISH', '갤러리 발행', 'GALLERY', 'PUBLISH', '갤러리 콘텐츠 발행'),
    ('BLOG_READ', '소식 조회', 'BLOG', 'READ', '소식 콘텐츠 조회'),
    ('BLOG_WRITE', '소식 편집', 'BLOG', 'WRITE', '소식 콘텐츠 작성 및 변경'),
    ('BLOG_PUBLISH', '소식 발행', 'BLOG', 'PUBLISH', '소식 콘텐츠 발행'),
    ('INQUIRY_READ', '문의 조회', 'INQUIRY', 'READ', '고객 문의 조회'),
    ('INQUIRY_WRITE', '문의 편집', 'INQUIRY', 'WRITE', '고객 문의 상태와 답변 변경'),
    ('SITE_BRAND_READ', '브랜드 조회', 'SITE_BRAND', 'READ', '사이트 브랜드 설정 조회'),
    ('SITE_BRAND_WRITE', '브랜드 편집', 'SITE_BRAND', 'WRITE', '사이트 브랜드 설정 변경'),
    ('SITE_BRAND_PUBLISH', '브랜드 발행', 'SITE_BRAND', 'PUBLISH', '사이트 브랜드 설정 발행'),
    ('COURSE_READ', '과정 조회', 'COURSE', 'READ', '교육 과정 조회'),
    ('COURSE_WRITE', '과정 편집', 'COURSE', 'WRITE', '교육 과정 작성 및 변경'),
    ('STAFF_READ', '강사 조회', 'STAFF', 'READ', '강사 정보 조회'),
    ('STAFF_WRITE', '강사 편집', 'STAFF', 'WRITE', '강사 정보 작성 및 변경'),
    ('LESSON_PLAN_READ', '수업 계획 조회', 'LESSON_PLAN', 'READ', '수업 계획 조회'),
    ('LESSON_PLAN_WRITE', '수업 계획 편집', 'LESSON_PLAN', 'WRITE', '수업 계획 작성 및 변경'),
    ('LESSON_PLAN_PUBLISH', '수업 계획 발행', 'LESSON_PLAN', 'PUBLISH', '수업 계획 발행'),
    ('LESSON_LOG_READ', '수업 일지 조회', 'LESSON_LOG', 'READ', '수업 일지 조회'),
    ('LESSON_LOG_WRITE', '수업 일지 편집', 'LESSON_LOG', 'WRITE', '수업 일지 작성 및 변경'),
    ('MAKEUP_READ', '보강 조회', 'MAKEUP', 'READ', '보강 일정 조회'),
    ('MAKEUP_WRITE', '보강 편집', 'MAKEUP', 'WRITE', '보강 일정 작성 및 변경'),
    ('ENROLLMENT_READ', '등록 조회', 'ENROLLMENT', 'READ', '등록 진행 상태 조회'),
    ('ENROLLMENT_WRITE', '등록 편집', 'ENROLLMENT', 'WRITE', '등록 진행 상태 변경'),
    ('NOTIFICATION_READ', '안내 조회', 'NOTIFICATION', 'READ', '운영 안내 발송 내역 조회'),
    ('NOTIFICATION_SEND', '안내 발송', 'NOTIFICATION', 'SEND', '운영 안내 발송'),
    ('CONSENT_READ', '동의 조회', 'CONSENT', 'READ', '개인정보 동의 상태 조회'),
    ('CONSENT_WRITE', '동의 편집', 'CONSENT', 'WRITE', '개인정보 동의 상태 변경'),
    ('DATA_TRANSFER_IMPORT', '데이터 가져오기', 'DATA_TRANSFER', 'IMPORT', '운영 데이터 가져오기'),
    ('DATA_TRANSFER_EXPORT', '데이터 내보내기', 'DATA_TRANSFER', 'EXPORT', '운영 데이터 내보내기'),
    ('RETENTION_READ', '보존 정책 조회', 'RETENTION', 'READ', '데이터 보존 정책 조회'),
    ('RETENTION_EXECUTE', '보존 작업 실행', 'RETENTION', 'EXECUTE', '데이터 보존 및 파기 작업 실행'),
    ('ADMIN_ACCOUNT_READ', '관리자 계정 조회', 'ADMIN_ACCOUNT', 'READ', '관리자 계정과 역할 조회'),
    ('ADMIN_ACCOUNT_WRITE', '관리자 계정 편집', 'ADMIN_ACCOUNT', 'WRITE', '관리자 계정과 역할 변경'),
    ('SECURITY_POLICY_READ', '보안 정책 조회', 'SECURITY_POLICY', 'READ', '관리자 보안 정책 조회'),
    ('SECURITY_POLICY_WRITE', '보안 정책 편집', 'SECURITY_POLICY', 'WRITE', '관리자 보안 정책 변경'),
    ('AUDIT_READ', '감사 로그 조회', 'AUDIT', 'READ', '관리자 감사 로그 조회')
on conflict (code) do update set
    name = excluded.name,
    domain = excluded.domain,
    operation = excluded.operation,
    description = excluded.description,
    active = true;

insert into public.admin_role_permission (admin_role_id, admin_permission_id)
select role.id, permission.id
from public.admin_role role
cross join public.admin_permission permission
where role.code = 'OWNER' and permission.active
on conflict do nothing;

insert into public.admin_role_permission (admin_role_id, admin_permission_id)
select role.id, permission.id
from public.admin_role role
join public.admin_permission permission on permission.code = any (array[
    'DASHBOARD_READ', 'STUDENT_READ', 'STUDENT_WRITE', 'STUDENT_STATUS_WRITE',
    'STUDENT_NOTE_READ', 'STUDENT_NOTE_WRITE', 'STUDENT_SCHEDULE_WRITE',
    'SCHEDULE_READ', 'SCHEDULE_WRITE', 'SCHEDULE_PUBLISH', 'ATTENDANCE_READ',
    'ATTENDANCE_WRITE', 'ATTENDANCE_CLOSE', 'COURSE_READ', 'COURSE_WRITE',
    'STAFF_READ', 'LESSON_PLAN_READ', 'LESSON_PLAN_WRITE', 'LESSON_PLAN_PUBLISH',
    'LESSON_LOG_READ', 'LESSON_LOG_WRITE', 'MAKEUP_READ', 'MAKEUP_WRITE',
    'ENROLLMENT_READ', 'ENROLLMENT_WRITE', 'INQUIRY_READ', 'INQUIRY_WRITE',
    'NOTIFICATION_READ', 'NOTIFICATION_SEND', 'CONSENT_READ', 'CONSENT_WRITE',
    'DATA_TRANSFER_IMPORT'
])
where role.code = 'OPERATOR'
on conflict do nothing;

insert into public.admin_role_permission (admin_role_id, admin_permission_id)
select role.id, permission.id
from public.admin_role role
join public.admin_permission permission on permission.code = any (array[
    'DASHBOARD_READ', 'SITE_BRAND_READ', 'SITE_BRAND_WRITE', 'SITE_BRAND_PUBLISH',
    'CONTENT_PROFILE_READ', 'CONTENT_PROFILE_WRITE', 'CONTENT_PROGRAM_READ',
    'CONTENT_PROGRAM_WRITE', 'MEDIA_WRITE', 'GALLERY_READ', 'GALLERY_WRITE',
    'GALLERY_PUBLISH', 'BLOG_READ', 'BLOG_WRITE', 'BLOG_PUBLISH'
])
where role.code = 'CONTENT'
on conflict do nothing;

insert into public.admin_role_permission (admin_role_id, admin_permission_id)
select role.id, permission.id
from public.admin_role role
join public.admin_permission permission on permission.code = any (array[
    'DASHBOARD_READ', 'STUDENT_READ', 'STUDENT_TUITION_WRITE', 'TUITION_POLICY_READ',
    'TUITION_POLICY_WRITE', 'TUITION_BILLING_READ', 'TUITION_BILLING_WRITE',
    'TUITION_PAYMENT_READ', 'TUITION_PAYMENT_WRITE', 'TUITION_ADJUSTMENT_READ',
    'TUITION_ADJUSTMENT_WRITE', 'TUITION_REFUND_WRITE', 'TUITION_RECEIPT_READ',
    'TUITION_RECEIPT_ISSUE', 'FINANCE_READ', 'FINANCE_WRITE', 'FINANCE_IMPORT',
    'NOTIFICATION_READ', 'NOTIFICATION_SEND', 'DATA_TRANSFER_IMPORT',
    'DATA_TRANSFER_EXPORT'
])
where role.code = 'FINANCE'
on conflict do nothing;

insert into public.admin_user (
    id, email, password_hash, display_name, password_must_change,
    temporary_password_expires_at, created_by
)
values (
    '20000000-0000-0000-0000-000000000001',
    'owner@rami.local',
    extensions.crypt('LocalOnly!Change123', extensions.gen_salt('bf', 12)),
    '로컬 원장',
    true,
    now() + interval '24 hours',
    null
)
on conflict (id) do nothing;

insert into public.admin_user_role (admin_user_id, admin_role_id, assigned_by)
select
    '20000000-0000-0000-0000-000000000001'::uuid,
    role.id,
    null
from public.admin_role role
where role.code = 'OWNER'
on conflict (admin_user_id) do update set admin_role_id = excluded.admin_role_id;

insert into public.admin_session_policy (
    id, version, max_failed_attempts, lock_duration_minutes,
    idle_timeout_minutes, absolute_timeout_minutes, expiry_warning_minutes,
    change_reason, created_by
)
values (
    '30000000-0000-0000-0000-000000000001',
    1,
    5,
    30,
    60,
    720,
    5,
    '로컬 개발 환경의 최초 관리자 세션 정책',
    '20000000-0000-0000-0000-000000000001'
)
on conflict (version) do nothing;
