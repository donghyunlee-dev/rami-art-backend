# 데이터베이스 통합 적용 기준

기존 Backend와 관리자 API는 같은 Supabase 프로젝트를 사용하지만, 현재 데이터 계약은 서로 다른 스키마를 사용한다. 모노레포에서는 관리자 migration과 seed를 루트 `supabase/`에서만 관리한다. 이 문서의 `supabase/` 경로는 저장소 루트 기준이며, Backend 테스트는 상위 디렉터리의 파일을 사용한다.

## 스키마 경계

| 영역 | 스키마 | 변경 원본 | 실행 방식 |
| --- | --- | --- | --- |
| 기존 공개·갤러리 API | `rami_art_studio` | `src/main/resources/db/migration` | Backend 시작 시 Flyway |
| 관리자 업무 API | `public` | `supabase/migrations` 및 `supabase/seed.sql` | Supabase CLI dry-run 후 승인 적용 |

두 migration 집합은 같은 테이블을 수정하지 않는다. 기존 Flyway migration은 관리자 migration을 자동 실행하지 않으며, 관리자 migration도 기존 `rami_art_studio` 객체를 삭제하거나 변환하지 않는다.

## 적용 순서

- Supabase 백업·PITR 상태와 현재 migration 이력을 먼저 저장한다.
- 현재 운영 DB의 `public` 테이블·행 수·RLS·`rami_backend` 권한을 읽기 전용으로 확인한다.
- `202607120001`부터 마지막으로 원격에 확인된 baseline까지는 이미 적용된 것으로 대사한다.
- `202609*` 관리자 보강 migration은 `db push --dry-run` 결과를 확인한 뒤 별도 승인으로 적용한다.
- 적용 후 관리자 Backend readiness, 로그인, 핵심 조회 API를 확인한다.
- 기존 `/api/v1/**` smoke test가 통과한 뒤에만 Render 서비스 트래픽을 전환한다.

## 금지 사항

- 운영 DB에서 `db reset`, 전체 seed 재실행, 기존 스키마 삭제를 수행하지 않는다.
- `rami_art_studio`와 `public` 사이의 자동 테이블 복사·rename을 수행하지 않는다.
- Supabase migration 이력을 수동으로 수정하지 않는다.
- Flyway 시작 시 관리자 migration을 classpath로 추가해 운영 기동 중 자동 DDL을 실행하지 않는다.

경계 검사는 `scripts/verify-schema-boundary.ps1`로 반복 실행한다. 이 검사는 원격 DB에 연결하지 않는다.
