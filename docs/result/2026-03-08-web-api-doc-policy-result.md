# 작업 맥락
- 요청: API-SPEC 외에 웹 개발에서 직접 사용할 API 연동 문서를 만들고, 단계 진행 때마다 문서를 갱신하는 정책 적용.

# 변경된 파일 목록
- `docs/guide/WEB-API-INTEGRATION.md`
- `docs/plan/PHASE-PLAN.md`

# 핵심 변경 내용
- 웹 연동 중심 문서 신규 작성:
  - 전체 호출 규칙(Base URL, 응답 포맷)
  - 인증 정책(Bearer/JWT, refresh 재시도 흐름)
  - 현재 구현된 system API 요청/응답 JSON 예시
  - 단계별 업데이트 로그 섹션
- Phase 계획 규칙에 문서 갱신 의무 추가:
  - 각 단계 완료 시 `WEB-API-INTEGRATION.md` 업데이트

# 리스크/후속 작업
- Phase 2(Auth API) 완료 시 실제 토큰 발급/재발급 응답 스키마로 본 문서를 즉시 업데이트해야 함.
