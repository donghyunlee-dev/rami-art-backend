# 작업 맥락
- 요청: Phase 2(Auth API) 개발 진행.
- 목표: JWT 기반 인증 API(`/login`, `/logout`, `/refresh`, `/me`, `/password`) 구현 및 웹 연동 문서 업데이트.

# 변경된 파일 목록
- `build.gradle`
- `src/main/resources/application-dev.yml`
- `src/test/resources/application.yml`
- `src/main/resources/db/migration/V2__add_admin_password_hash.sql`
- `src/main/resources/db/migration/V3__create_admin_refresh_tokens.sql`
- `src/main/java/com/rami/artstudio/common/config/AppSecurityProperties.java`
- `src/main/java/com/rami/artstudio/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/rami/artstudio/security/*`
- `src/main/java/com/rami/artstudio/auth/*`
- `src/test/java/com/rami/artstudio/auth/AuthApiIntegrationTests.java`
- `docs/guide/WEB-API-INTEGRATION.md`
- `docs/plan/2026-03-08-phase2-auth-api-plan.md`

# 핵심 변경 내용
- Spring Security + JWT 기반 인증 체인 구성
  - Bearer 토큰 필터, 401/403 JSON 핸들러 추가
- Auth API 구현
  - `POST /api/v1/auth/login`
  - `POST /api/v1/auth/refresh`
  - `POST /api/v1/auth/logout`
  - `GET /api/v1/auth/me`
  - `PATCH /api/v1/auth/password`
- DB 확장
  - 관리자 비밀번호 해시 컬럼 추가(`admin_users.password_hash`)
  - 리프레시 토큰 저장 테이블 추가(`admin_refresh_tokens`)
- 부트스트랩 관리자 계정 자동 보정
  - 설정값 기반 기본 관리자 생성/비밀번호 해시 설정
- 웹 연동 문서(요청/응답 JSON, 인증 흐름) Phase 2 기준 반영

# 리스크/후속 작업
- 현재 refresh token은 서버 DB 저장 + 문자열 토큰 방식이며, 쿠키 전달 전략은 웹 단계에서 최종 확정 필요.
- 역할 기반 권한(`admin`, `super_admin`) 세분화는 다음 단계에서 인가 정책으로 확장 필요.
