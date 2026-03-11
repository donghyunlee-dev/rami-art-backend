# 2026-03-11 Phase 3 Classes API Test

## Test Scope
- Public classes list/detail read flow
- Admin classes authentication
- Admin create, update, sort, delete flow
- Flyway/JPA context boot with existing Supabase PostgreSQL data

## Command
- `./gradlew test --no-daemon`

## Result
- Passed
- `BUILD SUCCESSFUL in 2m 48s`

## Verified Cases
- Public `GET /api/v1/classes` returns the seeded active class list
- Public `GET /api/v1/classes/{id}` returns a seeded class detail
- Admin `GET /api/v1/admin/classes` requires authentication
- Admin authenticated flow supports create, sort patch, update, and soft delete
- Security config permits `/api/v1/classes/**` without a bearer token

## Test Notes
- The admin CRUD integration test runs inside a rollback transaction so seeded data is not permanently altered.
- Tests run against Supabase PostgreSQL as required by the repository rule.
