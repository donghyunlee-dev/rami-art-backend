# 테스트 대상
- 문서 변경 작업: 테스트 규칙 업데이트 반영 여부

# 테스트 케이스 목록
- `AGENTS.md`에 Supabase PostgreSQL 필수 문구 존재 확인
- `AGENTS.md`에 인메모리 DB 금지 문구 존재 확인
- `AGENTS.md`에 ramiartstudio.com 기반 테스트 데이터 문구 존재 확인

# 실행 명령
```bash
sed -n '1,260p' AGENTS.md
```

# 실제 결과 (성공/실패, 로그 요약)
- 성공
- `Testing Guidelines` 섹션에 4개 규칙(인메모리 금지, Supabase 사용, 웹 콘텐츠 기반 데이터, 추적성 기록) 반영 확인.

# 이슈 및 조치 사항
- 코드/런타임 변경이 아닌 문서 변경이므로 애플리케이션 테스트는 수행하지 않음.
