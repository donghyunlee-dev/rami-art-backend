# 단계 계획: DB 접속 및 스키마 생성

## 목표
- `application-dev.yml` 기반으로 Supabase PostgreSQL 접속 가능 여부 확인
- `rami_art_studio` 스키마 및 초기 테이블 생성 코드 추가
- 실제 DB에 스키마/테이블 생성 검증

## 작업 단계
1. DB 연결 설정 정리 (`application-dev.yml`)
2. DB 마이그레이션 기반 의존성 추가 (`build.gradle`)
3. 초기 스키마/테이블 SQL 작성 (`V1__init_rami_art_studio_schema.sql`)
4. 실제 Supabase 연결 테스트 및 SQL 실행
5. 생성 결과(테이블 목록) 검증 및 문서화

## 완료 기준
- Supabase 연결 성공 로그 확보
- `rami_art_studio` 스키마 하위 테이블 9개 생성 확인
- 변경 파일/테스트 결과 문서 저장 완료
