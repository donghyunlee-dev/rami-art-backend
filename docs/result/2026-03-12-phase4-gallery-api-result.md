# 2026-03-12 Phase 4 Gallery API Result

## Context
- Phase 4 Gallery API를 구현했다.
- Public 조회와 Admin 관리 API를 기존 인증/응답 포맷 규칙에 맞춰 추가했다.
- 프론트 연동 문서를 현재 구현 기준으로 갱신했다.

## Changed Files
- `src/main/java/com/rami/artstudio/gallery/domain/GalleryCategory.java`
- `src/main/java/com/rami/artstudio/gallery/domain/GalleryWork.java`
- `src/main/java/com/rami/artstudio/gallery/dto/GalleryDtos.java`
- `src/main/java/com/rami/artstudio/gallery/infra/GalleryCategoryRepository.java`
- `src/main/java/com/rami/artstudio/gallery/infra/GalleryWorkRepository.java`
- `src/main/java/com/rami/artstudio/gallery/service/GalleryService.java`
- `src/main/java/com/rami/artstudio/gallery/service/GalleryServiceImpl.java`
- `src/main/java/com/rami/artstudio/gallery/storage/GalleryImageStorage.java`
- `src/main/java/com/rami/artstudio/gallery/storage/SupabaseGalleryImageStorage.java`
- `src/main/java/com/rami/artstudio/gallery/api/PublicGalleryController.java`
- `src/main/java/com/rami/artstudio/gallery/api/AdminGalleryController.java`
- `src/main/java/com/rami/artstudio/common/config/AppStorageProperties.java`
- `src/main/java/com/rami/artstudio/security/SecurityConfig.java`
- `src/main/resources/db/migration/V5__seed_gallery_content.sql`
- `src/test/java/com/rami/artstudio/gallery/GalleryApiIntegrationTests.java`
- `docs/plan/2026-03-12-phase4-gallery-api-plan.md`
- `docs/test/2026-03-12-phase4-gallery-api-test.md`
- `docs/result/2026-03-12-phase4-gallery-api-result.md`
- `docs/guide/WEB-API-INTEGRATION.md`
- `docs/guide/API-SPEC.md`

## Implemented APIs
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

## Key Decisions
- 작품 생성은 `multipart/form-data`로 이미지를 직접 받아 Supabase Storage에 업로드한다.
- 작품 수정은 multipart 기반이며 `image` part가 있으면 기존 이미지를 교체한다.
- Storage bucket 기본값은 `pub_rami_art_bucket`이다.
- 공개 목록과 관리자 목록 모두 페이지네이션 응답을 사용한다.
- 공개 작품 목록은 `categoryId`, `ageGroup`, `featured`, `keyword` 필터를 지원한다.
- 관리자 작품 목록은 `isActive` 필터를 추가로 지원한다.

## Notes
- 카테고리는 `slug` 고유성을 유지하며 중복 시 `409 CONFLICT`를 반환한다.
- 작품 삭제는 소프트 삭제이며 Storage 파일도 함께 삭제한다.
- Gallery API 공개 경로는 Security 설정에서 인증 제외 처리했다.
- Storage 연동에 필요한 설정값은 `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_GALLERY_BUCKET`이다.
