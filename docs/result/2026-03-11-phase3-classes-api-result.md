# 2026-03-11 Phase 3 Classes API Result

## Context
- Implemented the Phase 3 Classes API for both public website consumption and authenticated admin management.
- Updated the web integration and API specification documents together with the code change.

## Changed Files
- `docs/plan/2026-03-11-phase3-classes-api-plan.md`
- `src/main/java/com/rami/artstudio/classes/domain/ArtClass.java`
- `src/main/java/com/rami/artstudio/classes/domain/ClassTag.java`
- `src/main/java/com/rami/artstudio/classes/infra/ArtClassRepository.java`
- `src/main/java/com/rami/artstudio/classes/infra/ClassTagRepository.java`
- `src/main/java/com/rami/artstudio/classes/dto/ClassDtos.java`
- `src/main/java/com/rami/artstudio/classes/service/ClassService.java`
- `src/main/java/com/rami/artstudio/classes/service/ClassServiceImpl.java`
- `src/main/java/com/rami/artstudio/classes/api/PublicClassController.java`
- `src/main/java/com/rami/artstudio/classes/api/AdminClassController.java`
- `src/main/java/com/rami/artstudio/common/exception/ConflictException.java`
- `src/main/java/com/rami/artstudio/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/rami/artstudio/security/SecurityConfig.java`
- `src/test/java/com/rami/artstudio/classes/ClassesApiIntegrationTests.java`
- `docs/guide/WEB-API-INTEGRATION.md`
- `docs/guide/API-SPEC.md`

## Implemented APIs
- Public
  - `GET /api/v1/classes`
  - `GET /api/v1/classes/{id}`
- Admin
  - `GET /api/v1/admin/classes`
  - `POST /api/v1/admin/classes`
  - `PUT /api/v1/admin/classes/{id}`
  - `DELETE /api/v1/admin/classes/{id}`
  - `PATCH /api/v1/admin/classes/{id}/sort`

## Notes
- Public class APIs are now explicitly excluded from authentication in Spring Security.
- The service layer is interface-based and hides repository details behind deterministic methods.
- One non-deleted class per `age_group` is enforced. Duplicate create/update requests return `409 CONFLICT`.
- Delete uses soft delete via `deleted_at`.
