# 작업 맥락
- 요청: `application-dev.yml` DB 정보로 접속 테스트를 수행하고, PostgreSQL에 `rami_art_studio` 스키마 및 테이블을 실제 생성.

# 변경된 파일 목록
- `build.gradle`
- `src/main/resources/application-dev.yml`
- `src/main/resources/db/migration/V1__init_rami_art_studio_schema.sql`
- `gradlew` (실행 권한 부여)

# 핵심 변경 내용
- 빌드 의존성 추가:
  - `spring-boot-starter-data-jpa`
  - `flyway-core`, `flyway-database-postgresql`
  - `postgresql` 드라이버
- `application-dev.yml` 보정:
  - p6spy 제거, PostgreSQL 드라이버로 변경
  - `flyway` 스키마(`rami_art_studio`) 설정 추가
  - JPA 기본 스키마 설정 추가
- Flyway SQL 마이그레이션 추가:
  - `rami_art_studio` 스키마 생성
  - 테이블 9개 생성
  - `updated_at` 자동 갱신 트리거 함수/트리거 생성

# 리스크/후속 작업
- 현재 DB 비밀번호가 설정 파일 기본값에 포함되어 있어, 다음 단계에서 반드시 환경변수 기반으로 전환 필요.
- 다음 구현 단계에서 엔티티/리포지토리와 마이그레이션 버전 정책을 정식 확정 필요.
