# 테스트 대상
- Supabase PostgreSQL 접속 가능 여부
- `rami_art_studio` 스키마 및 테이블 생성 SQL 실행 결과

# 테스트 케이스 목록
- `application-dev.yml`의 접속 정보로 DB 연결
- 마이그레이션 SQL 실행
- 생성된 스키마 테이블 목록 조회

# 실행 명령
```bash
/tmp/rami-dbtest-venv/bin/python - <<'PY'
# psycopg2로 application-dev.yml의 DB 정보 사용
# 연결 후 V1__init_rami_art_studio_schema.sql 실행
# information_schema.tables 조회
PY
```

# 실제 결과 (성공/실패, 로그 요약)
- 성공
- 연결 확인:
  - `CONNECTED postgres postgres`
  - `POSTGRES_VERSION PostgreSQL 17.6`
- 생성 확인:
  - `TABLE_COUNT 9`
  - `admin_users, blog_posts, class_tags, classes, contacts, gallery_categories, gallery_works, page_sections, site_settings`

# 테스트 데이터 기준
- 소스 기준: `https://www.ramiartstudio.com/`
- 수집일: `2026-03-08`
- 본 단계는 스키마 생성 검증 단계로, 실제 콘텐츠 시드 입력은 다음 API 구현 단계에서 수행 예정.

# 이슈 및 조치 사항
- JDBC 전용 파라미터(`prepareThreshold`, `preferQueryMode`)는 psycopg2 DSN에서 제외 후 검증 수행.
