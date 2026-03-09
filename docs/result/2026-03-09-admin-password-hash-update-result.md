# 작업 맥락
- 요청: Supabase Auth로 생성된 관리자 계정에 대해 `admin_users.password_hash`를 생성해 저장.
- 대상 관리자 ID: `5db12b1c-daa6-4002-8240-461b42563b3f`

# 변경된 파일 목록
- 코드/설정 파일 변경 없음
- DB 변경:
  - `rami_art_studio.admin_users.password_hash` 업데이트

# 핵심 변경 내용
- 대상 계정 확인:
  - `admin_users.id = 5db12b1c-daa6-4002-8240-461b42563b3f`
  - `email = leedh84@gmail.com`
- 비밀번호 `password123`을 BCrypt 해시로 생성 후 `password_hash` 컬럼에 저장

# 리스크/후속 작업
- 현재 인증 구현은 `admin_users.password_hash` 기반이다.
- 설계 문서의 최종 방향은 Supabase Auth 직접 연동이므로, 이후 인증 구조를 정렬할지 결정이 필요하다.
