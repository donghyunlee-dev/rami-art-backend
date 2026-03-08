# 작업 맥락
- 요청: 테스트 규칙에 `Supabase PostgreSQL 기반 테스트`와 `ramiartstudio.com 페이지 콘텐츠 기반 기본 데이터` 원칙 추가.

# 변경된 파일 목록
- `AGENTS.md`

# 핵심 변경 내용
- Testing Guidelines에 아래 규칙 추가:
  - 인메모리 DB(H2/HSQL/Derby) 사용 금지
  - 통합 테스트는 Supabase PostgreSQL 대상으로 실행
  - 기본 테스트 데이터는 `https://www.ramiartstudio.com/` 페이지 콘텐츠에서 도출
  - 테스트 로그에 소스 페이지 경로/수집일 기록

# 리스크/후속 작업
- 실제 테스트 자동화 시 Supabase 접속 정보(.env)와 시드 정책(초기화/롤백)을 추가로 정의해야 함.
