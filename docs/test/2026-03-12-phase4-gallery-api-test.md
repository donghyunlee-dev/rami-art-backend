# 2026-03-12 Phase 4 Gallery API Test

## Test Scope
- Public gallery categories/works read flow
- Gallery public filter and pagination flow
- Admin gallery authentication
- Admin category CRUD flow
- Admin work create, update, sort, delete flow
- Seeded gallery data boot with Flyway/JPA

## Command
- `./gradlew test --no-daemon`

## Result
- Passed
- `BUILD SUCCESSFUL in 1m 42s`

## Verified Cases
- Public `GET /api/v1/gallery/categories` returns active category list
- Public `GET /api/v1/gallery/works` returns paged content structure
- Public `GET /api/v1/gallery/works/{id}` returns a seeded work detail
- Public works list supports category filter
- Admin gallery APIs require authentication
- Admin authenticated flow supports category create/update/delete
- Admin authenticated flow supports multipart work create/list/sort/update/delete
- Gallery work create/update/delete flow calls the storage upload/delete abstraction correctly

## Test Notes
- Tests run against Supabase PostgreSQL with Flyway migration/seed applied
- Admin CRUD integration tests run inside rollback transactions so seeded data is not permanently altered
- Storage I/O is replaced with a mock bean in tests, so live Supabase upload/delete itself was not exercised in this test run
