# 2026-03-11 Phase 3 Classes API Plan

## Context
- Request: implement Phase 3 Classes API and update the API documentation for web integration.
- Existing schema already contains `classes` and `class_tags`.
- Public site seed data is already present in Supabase PostgreSQL.

## Scope
- Public APIs
  - `GET /api/v1/classes`
  - `GET /api/v1/classes/{id}`
- Admin APIs
  - `GET /api/v1/admin/classes`
  - `POST /api/v1/admin/classes`
  - `PUT /api/v1/admin/classes/{id}`
  - `DELETE /api/v1/admin/classes/{id}`
  - `PATCH /api/v1/admin/classes/{id}/sort`
- Documentation updates
  - `docs/guide/WEB-API-INTEGRATION.md`
  - `docs/guide/API-SPEC.md`

## Design Rules
- Keep controller-service-repository separation.
- Define service interfaces first and hide persistence details behind the service boundary.
- Enforce deterministic behavior by allowing at most one non-deleted class per `age_group`.
- Preserve soft delete semantics with `deleted_at`.

## Validation
- Add integration tests for public class reads and authenticated admin CRUD/sort flows.
- Run `./gradlew test --no-daemon`.
