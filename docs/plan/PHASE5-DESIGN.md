# 🧭 콘텐츠·문의·사이트 설정 API 설계 정합화 계획

## 목적

Phase 5(Blog·Contact·Settings)를 구현하기 전에 관리자 화면 설계와 백엔드 API 계약을 하나의 기준으로 정리한다. 현재 상세 설계는 웹 프로젝트의 관리자 문서에 있고, 백엔드 `API-SPEC.md`에는 해당 API가 아직 미구현으로 남아 있다.

## 기준 문서

- 관리자 Blog 계약: [`blog-post.md`](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/blog-post.md)
- 관리자 문의 계약: [`inquiry.md`](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/inquiry.md)
- 사이트 브랜드 계약: [`site-brand.md`](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/site-brand.md)
- 데이터 구조: [`DATA-SPEC.md`](../guide/DATA-SPEC.md)
- 현재 구현 API: `docs/guide/API-SPEC.md`

## 현재 확인 결과

- Blog·Contact·Settings의 필드, 권한, 멱등성, 감사 로그, 동시성 규칙은 관리자 문서에 정의되어 있다.
- 백엔드 `API-SPEC.md`는 현재 구현된 API만 기록하는 정책이므로 세 영역을 미구현으로 표시하고 있다.
- 관리자 문서의 경로(`/admin/...`)와 백엔드 공통 계약의 버전 경로(`/api/v1/...`)가 다르다.
- 따라서 상세 문서를 그대로 복사하지 않고, 백엔드 구현 시 `/api/v1/admin/...` 또는 공개 API의 최종 경로를 먼저 확정해야 한다.

## 구현 전 결정 사항

- 관리자 Blog: `/api/v1/admin/blog-posts` 계열의 목록·초안·저장·미리보기·발행 경로 확정
- 공개/관리자 문의: `/api/v1/public/inquiries`, `/api/v1/admin/inquiries` 계열 경로 확정
- 사이트 설정: `/api/v1/admin/site-brand` 계열 경로와 공개 조회 제공 여부 확정
- 공통 응답을 현재 백엔드 envelope(`success`, `data`, `message`, `error`)와 관리자 문서의 `requestId`, `fieldErrors` 규칙으로 통합
- 세 영역의 migration, 권한, idempotency, audit, version 충돌 오류를 구현 계약에 반영

## 완료 기준

- 최종 경로와 HTTP method가 `docs/guide/API-SPEC.md`에 반영된다.
- 요청·응답·오류·권한·데이터 출처가 각 엔드포인트별로 문서화된다.
- `docs/guide/WEB-API-INTEGRATION.md`에 프론트엔드 호출 예시가 추가된다.
- 구현 후 `docs/test/`와 `docs/result/`에 실제 검증 결과를 기록한다.

이 문서는 구현 완료를 의미하지 않으며, Phase 5 구현 전 계약 충돌을 방지하기 위한 백엔드 기준 문서다.
