# 테스트 대상
- `logback` 설정 추가 이후 전체 빌드 및 테스트 정상 동작 여부

# 테스트 케이스 목록
- Gradle 전체 빌드
- Spring Boot 컨텍스트 로드 테스트
- Auth API 통합 테스트

# 실행 명령
```bash
/root/.gradle/wrapper/dists/gradle-8.14.4-bin/92wwslzcyst3phie3o264zltu/gradle-8.14.4/bin/gradle clean build --no-daemon
```

# 실제 결과 (성공/실패, 로그 요약)
- 성공
- 결과 요약:
  - `BUILD SUCCESSFUL in 2m 35s`
  - `9 actionable tasks: 9 executed`
- 테스트 포함 항목:
  - `RamiArtBackendApplicationTests`
  - `AuthApiIntegrationTests`

# 테스트 데이터 기준
- DB: Supabase PostgreSQL
- 테스트 설정: `src/test/resources/application.yml`

# 이슈 및 조치 사항
- 1차 실패 원인:
  - `spring.application.name` placeholder 누락
- 조치:
  - `src/test/resources/application.yml`에 `spring.application.name: rami-art-studio-api` 추가 후 재실행 성공
