# 작업 맥락
- 요청: 프론트엔드 API 호출 시 발생하는 CORS 문제를 해소하기 위해 헤더 기준 전역 허용 설정 적용.

# 변경된 파일 목록
- `src/main/java/com/rami/artstudio/security/SecurityConfig.java`
- `src/test/java/com/rami/artstudio/security/CorsIntegrationTests.java`

# 핵심 변경 내용
- Spring Security에 전역 CORS 설정 추가
  - `allowedOriginPatterns = *`
  - `allowedMethods = *`
  - `allowedHeaders = *`
  - `exposedHeaders = *`
  - `allowCredentials = false`
- 모든 경로(`/**`)에 동일한 CORS 정책 적용
- Preflight 요청 허용 여부를 검증하는 통합 테스트 추가

# 리스크/후속 작업
- 현재 설정은 개발/임시 운영 대응용으로 매우 개방적이다.
- 실제 운영 안정화 단계에서는 허용 origin을 프론트 도메인으로 제한하는 것이 맞다.
