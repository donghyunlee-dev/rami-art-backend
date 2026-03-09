# 테스트 대상
- 관리자 계정 `password_hash` 업데이트 및 실제 로그인 가능 여부

# 테스트 케이스 목록
- 대상 UUID가 `admin_users.id` 또는 `auth_user_id` 중 어디에 매칭되는지 조회
- BCrypt 해시 저장 후 DB 반영 확인
- `/api/v1/auth/login`으로 실제 로그인 성공 확인

# 실행 명령
```bash
curl -X POST http://localhost:9000/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"leedh84@gmail.com","password":"password123"}'
```

# 실제 결과 (성공/실패, 로그 요약)
- 성공
- 대상 계정 확인:
  - `id = 5db12b1c-daa6-4002-8240-461b42563b3f`
  - `email = leedh84@gmail.com`
- 해시 저장 확인:
  - BCrypt prefix 확인: `$2b$10$`
- 로그인 확인:
  - `success = true`
  - `accessToken`, `refreshToken` 발급 확인

# 테스트 데이터 기준
- DB: Supabase PostgreSQL
- 계정 이메일: `leedh84@gmail.com`
- 적용 비밀번호: `password123`

# 이슈 및 조치 사항
- 임시 Python 가상환경(`/tmp/rami-dbtest-venv`)을 생성해 PostgreSQL 접속과 BCrypt 해시 생성을 수행했다.
