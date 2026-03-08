# 단계 계획: Phase 2 Auth API

## 목표
- 인증 API 구현: `/api/v1/auth/login`, `/logout`, `/refresh`, `/me`, `/password`
- JWT Bearer 인증과 보호 경로 접근 제어 적용
- Supabase PostgreSQL 기반으로 관리자 계정/리프레시 토큰 저장

## 작업 단계
1. 보안 의존성 추가 (Spring Security, JWT)
2. DB 마이그레이션 추가
   - `admin_users.password_hash` 컬럼
   - `admin_refresh_tokens` 테이블
3. Auth 도메인/서비스 인터페이스 및 구현 추가
4. JWT 필터, Security 설정, 401/403 응답 핸들러 추가
5. Auth API 컨트롤러 구현
6. 통합 테스트 및 문서 업데이트
   - `docs/guide/WEB-API-INTEGRATION.md`
   - `docs/result/*`, `docs/test/*`

## 완료 기준
- 로그인/재발급/로그아웃/내정보/비밀번호 변경 API 동작
- 인증 필요 경로에서 Bearer 토큰 검사 동작
- 테스트 성공 및 웹 연동 문서 JSON 예시 최신화
