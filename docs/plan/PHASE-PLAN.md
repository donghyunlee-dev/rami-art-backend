# Backend Phase Plan (Approval Gate)

## Goal
`docs/guide`의 PRD/API/DATA 스펙 기준으로 Spring Boot API를 단계별로 구현하고, 각 작업마다 변경사항/테스트/결과를 문서화한다.

## Process Rules
1. 각 단계 시작 전 계획 확인 요청
2. 승인 후 구현
3. 구현 후 테스트 수행
4. 아래 문서 2종 기록 후 다음 단계 승인 요청
   - `docs/test/*`: 테스트 케이스, 실행 명령, 실제 결과
   - `docs/result/*`: 작업 맥락, 변경 파일, 주요 결정 사항
5. 웹 연동 문서 갱신
   - `docs/guide/WEB-API-INTEGRATION.md`에 단계별 API 요청/응답 JSON, 인증 흐름, 호출 규칙 최신화

## Phase 1. API Foundation
- 범위
  - 공통 응답 포맷(`success/data/message`, `error`)
  - 전역 예외 처리 및 에러 코드
  - 기본 헬스체크/버전 API를 공통 포맷으로 정렬
  - 패키지 구조 정리(controller/service/repository/dto/domain)
- 완료 기준
  - `/api/v1/system/health`, `/api/v1/system/version`이 스펙 포맷으로 응답
  - 공통 예외 응답이 동작

## Phase 2. Auth API (P0)
- 범위
  - `/api/v1/auth/login`, `/logout`, `/refresh`, `/me`, `/password`
  - Spring Security + JWT 기반 인증 뼈대
- 완료 기준
  - 인증 필요/불필요 엔드포인트 접근 제어 확인

## Phase 3. Classes API (P0)
- 범위
  - 공개 조회: `/api/v1/classes`, `/api/v1/classes/{id}`
  - 관리자 CRUD: `/api/v1/admin/classes*`
- 완료 기준
  - 생성/조회/수정/삭제(소프트 삭제)/정렬 변경 API 동작

## Phase 4. Gallery API (P0/P1)
- 범위
  - 카테고리, 작품 CRUD, 목록 필터/페이지네이션
- 완료 기준
  - 주요 조회/관리 API 및 검증 로직 동작

## Phase 5. Blog + Contact + Settings API
- 범위
  - 블로그 게시글, 문의 관리, 사이트 설정 API
- 완료 기준
  - PRD P0 기능 우선 반영

## Phase 6. Hardening & QA
- 범위
  - 입력 검증 고도화, 로깅/모니터링, 문서 보강
- 완료 기준
  - 핵심 API 회귀 테스트 통과
