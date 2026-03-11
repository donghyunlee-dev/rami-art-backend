# 2026-03-11 Public Site Seed Test

## Test Scope
- Validate the new Flyway seed migration.
- Confirm the seeded records exist in Supabase PostgreSQL.

## Commands
- `./gradlew test --no-daemon`
- Temporary JDBC verification using the PostgreSQL driver from the Gradle cache

## Results
- `./gradlew test --no-daemon`: passed
- JDBC verification: passed

## Verified Values
- `site_settings_count=1`
- `page_sections_home_about_count=6`
- `active_core_classes_count=3`
- `class_tags_count=15`
- `site_settings_summary=010-6858-3758 | school579@naver.com | 인천광역시 남동구 호구포로 920 1층 107호`
- Page sections verified:
  - `about | intro | 라미아트 소개`
  - `about | philosophy | 교육 철학`
  - `about | teacher | 김보람 원장`
  - `about | facilities | 교습소 시설`
  - `home | hero | 창의력이 피어나는 곳`
  - `home | features | 라미아트 미술교습소의 특별함`
- Classes verified:
  - `kindergarten | 유치부 미술 수업 | 5-7세`
  - `elementary | 초등부 미술 수업 | 8-13세`
  - `middle | 중등부 미술 수업 | 14-16세`

## Failure and Fix History
- Initial test run failed because `sort_order` in the class tag insert query was ambiguous.
- The migration was corrected to qualify `tags.sort_order`, then tests and DB verification passed.
