# 🧭 콘텐츠·문의·사이트 설정 API 설계 정합화 계획

## 목적

Phase 5(Blog·Contact·Settings)를 구현하기 전에 관리자 화면 설계와 백엔드 API 계약을 하나의 기준으로 정리한다. 현재 상세 설계는 웹 프로젝트의 관리자 문서에 있고, 백엔드 `API-SPEC.md`에는 해당 API가 아직 미구현으로 남아 있다.

## 기준 문서

- 백엔드 Phase 5 API 명세: [`PHASE5-API-SPEC.md`](../guide/PHASE5-API-SPEC.md)
- 관리자 Blog 계약: [`blog-post.md`](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/blog-post.md)
- 관리자 문의 계약: [`inquiry.md`](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/inquiry.md)
- 사이트 브랜드 계약: [`site-brand.md`](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/site-brand.md)
- 데이터 구조: [`DATA-SPEC.md`](../guide/DATA-SPEC.md)
- 현재 구현 API: `docs/guide/API-SPEC.md`

## 확정된 기준

- Blog·Contact·Settings의 필드, 권한, 멱등성, 감사 로그, 동시성 규칙은 [`PHASE5-API-SPEC.md`](../guide/PHASE5-API-SPEC.md)에 백엔드 계약으로 정리했다.
- 기존 `/api/v1/**` Bearer JWT API는 유지하고, Phase 5는 현재 관리자 보안 필터가 사용하는 `/api/admin/**`, `/api/public/**` 경로를 사용한다.
- 관리자 인증은 DB-backed opaque session cookie를 유지한다. CSRF 토큰은 도입하지 않고, 현재 구현된 unsafe method Origin 검증을 사용한다.
- 문의 기준 테이블은 `public.inquiry`, 블로그는 `public.blog_post`, 사이트 설정은 `public.site_brand_config`로 고정한다.
- 블로그 본문은 현재 migration에 컬럼이 없으므로 구현 전에 `content` 추가 migration을 적용한다.

## 구현 전 필수 준비

- `public.blog_post.content` 추가 migration과 HTML sanitization 정책 적용
- Phase 5 엔드포인트의 controller·service·repository·test 구현
- `PHASE5-API-SPEC.md`의 오류 코드와 실제 `AdminGlobalExceptionHandler` 매핑 검증
- `WEB-API-INTEGRATION.md`의 Phase 5 호출 규칙과 프론트엔드 E2E 예시 유지

## 완료 기준

- 최종 경로와 HTTP method는 `docs/guide/PHASE5-API-SPEC.md`에 반영되어 있다.
- 요청·응답·오류·권한·데이터 출처가 각 엔드포인트별로 문서화되어 있다.
- `docs/guide/WEB-API-INTEGRATION.md`에 Phase 5 호출 기준이 연결되어 있다.
- 구현 후 `docs/test/`와 `docs/result/`에 실제 검증 결과를 기록한다.

이 문서는 구현 완료를 의미하지 않으며, Phase 5 구현 전 계약 충돌을 방지하기 위한 백엔드 기준 문서다.
