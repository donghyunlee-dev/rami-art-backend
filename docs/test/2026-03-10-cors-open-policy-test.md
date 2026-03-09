# 테스트 대상
- 전역 CORS 허용 정책 적용 이후 preflight 요청 정상 응답 여부
- 기존 인증/앱 컨텍스트 테스트 회귀 여부

# 테스트 케이스 목록
- `CorsIntegrationTests.preflightRequestIsAllowed`
- 기존 `AuthApiIntegrationTests`
- 기존 `RamiArtBackendApplicationTests`

# 실행 명령
```bash
/root/.gradle/wrapper/dists/gradle-8.14.4-bin/92wwslzcyst3phie3o264zltu/gradle-8.14.4/bin/gradle test --no-daemon
```

# 실제 결과 (성공/실패, 로그 요약)
- 성공
- 결과 요약:
  - `BUILD SUCCESSFUL in 2m 24s`
  - `5 actionable tasks: 4 executed, 1 up-to-date`

# 테스트 데이터 기준
- DB: Supabase PostgreSQL
- Preflight origin 예시: `https://ramiartstudio.com`

# 이슈 및 조치 사항
- 없음
