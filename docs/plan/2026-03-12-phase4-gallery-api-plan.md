# 2026-03-12 Phase 4 Gallery API Plan

## 목표
- 갤러리 카테고리/작품 API를 Spring Boot에 구현한다.
- 공개 조회와 관리자 CRUD를 분리하고, 목록 필터/페이지네이션을 제공한다.
- 기존 인증/공통 응답 포맷/예외 처리 규칙을 그대로 따른다.

## 범위
- Public
  - `GET /api/v1/gallery/categories`
  - `GET /api/v1/gallery/works`
  - `GET /api/v1/gallery/works/{id}`
- Admin
  - `GET /api/v1/admin/gallery/categories`
  - `POST /api/v1/admin/gallery/categories`
  - `PUT /api/v1/admin/gallery/categories/{id}`
  - `DELETE /api/v1/admin/gallery/categories/{id}`
  - `GET /api/v1/admin/gallery/works`
  - `POST /api/v1/admin/gallery/works`
  - `PUT /api/v1/admin/gallery/works/{id}`
  - `DELETE /api/v1/admin/gallery/works/{id}`
  - `PATCH /api/v1/admin/gallery/works/{id}/sort`

## 구현 원칙
- 기존 `classes` 패턴을 재사용한다.
- 파일 업로드는 이번 단계 범위에서 제외하고, 스키마에 정의된 `imageUrl`, `imagePath`를 JSON 요청으로 받는다.
- 작품 삭제는 `deleted_at` 기반 소프트 삭제로 처리한다.
- 카테고리 삭제는 실제 삭제를 사용한다.

## 검증 기준
- 공개 Gallery 조회는 인증 없이 접근 가능해야 한다.
- 관리자 Gallery API는 인증이 필요해야 한다.
- 작품 목록은 필터와 페이지네이션이 동작해야 한다.
- 카테고리 slug 중복은 `409 CONFLICT`로 처리되어야 한다.
- 생성/수정/삭제/정렬 변경 흐름이 통합 테스트로 검증되어야 한다.
