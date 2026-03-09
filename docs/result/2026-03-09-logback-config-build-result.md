# 작업 맥락
- 요청: `logback` 설정 추가 후 프로젝트 설정과 맞는지 확인하고 빌드/테스트 수행.

# 변경된 파일 목록
- `src/main/resources/logback-spring.xml`
- `src/main/resources/application.yml`
- `src/main/resources/spy.properties`
- `src/test/resources/application.yml`

# 핵심 변경 내용
- `logback-spring.xml`의 디버그 로거 패키지를 현재 코드베이스 기준으로 수정
  - `com.windsoft.apartment_parking_manager` -> `com.rami.artstudio`
- `application.yml`의 로깅 패키지 경로를 현재 프로젝트 패키지명으로 수정
- `spy.properties`의 존재하지 않는 커스텀 SQL 포매터 참조를 Logback 기본 포맷으로 교체
- `application.yml` 보정
  - `server.port`를 `${PORT:9000}`로 조정
  - `spring.application.name`과 `app.version` 기본값 보강
- 테스트 설정 보정
  - `src/test/resources/application.yml`에 `spring.application.name` 추가

# 리스크/후속 작업
- `spy.properties`는 현재 p6spy 의존성이 없어 런타임에 실제 사용되지는 않는다.
- `src/main/resources/application.yml`의 `DATASOURCE_*` placeholder는 로컬/배포 환경 변수 없이는 기본 런타임 부팅에 실패할 수 있으므로 배포 전에 환경변수 정의가 필요하다.
