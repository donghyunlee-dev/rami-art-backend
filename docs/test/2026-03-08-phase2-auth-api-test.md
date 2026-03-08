# 테스트 대상
- Phase 2 인증 API 구현 전체
- Supabase PostgreSQL 기반 통합 테스트

# 테스트 케이스 목록
- `AuthApiIntegrationTests.loginAndMeFlowWorks`
- `AuthApiIntegrationTests.meWithoutTokenReturns401`
- `AuthApiIntegrationTests.refreshAndLogoutFlowWorks`
- `AuthApiIntegrationTests.changePasswordWorks`
- `RamiArtBackendApplicationTests.contextLoads`

# 실행 명령
```bash
./gradlew test --no-daemon
```

# 실제 결과 (성공/실패, 로그 요약)
- 성공
- 결과 요약:
  - `BUILD SUCCESSFUL in 1m 21s`
  - `5 actionable tasks`
  - 인증 API 통합 테스트 4건 + 컨텍스트 로드 1건 통과

# 테스트 데이터 기준
- DB: Supabase PostgreSQL (인메모리 DB 미사용)
- 사이트 기준 데이터 출처: `https://www.ramiartstudio.com/`
- 수집일: `2026-03-08`
- 인증 테스트 계정: `admin@ramiartstudio.com` (부트스트랩 계정)

# 이슈 및 조치 사항
- 1차 실패: `@Modifying` 쿼리 트랜잭션 누락
  - 조치: `AdminRefreshTokenRepository.revokeAllByAdminUserId`에 `@Transactional` 추가
- 2차 실패: 테스트 JSON 키 네이밍 불일치
  - 조치: camelCase 기준으로 테스트 수정 및 dev 설정의 snake_case 전략 제거
