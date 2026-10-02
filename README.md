# rami-art-backend

Rami Art Studio API backend built with Spring Boot. The application now serves
the existing public API and the administrator API from one Render service.

## Requirements

- Java 21
- Gradle (or use `./gradlew`)

## Run

```bash
./gradlew bootRun
```

## Endpoints

- `GET /api/v1/system/health`
- `GET /api/v1/system/version`
- `POST /api/admin/auth/sessions`
- `GET /api/admin/students`
- `GET /api/admin/courses`
- `GET /api/admin/monthly-schedules/{yearMonth}`
- `GET /api/admin/attendance-sessions`

The legacy API remains under `/api/v1/**`; administrator workflows use
`/api/admin/**`. They use separate security chains and database schemas.

## Database migrations

The existing Flyway migrations under `src/main/resources/db/migration` remain
the source for the legacy `rami_art_studio` schema. Administrator schema,
RLS, grants and seed files are versioned under `supabase/migrations` and must
be applied through the Supabase CLI after a dry run. They are intentionally
not auto-applied by the legacy Flyway runner to avoid changing an existing
production database during application startup.

## Example Responses

`GET /api/v1/system/health`

```json
{
  "success": true,
  "data": {
    "status": "UP",
    "service": "rami-art-backend",
    "timestamp": "2026-03-08T13:00:00Z"
  },
  "message": "처리 완료",
  "error": null
}
```

`GET /api/v1/system/version`

```json
{
  "success": true,
  "data": {
    "service": "rami-art-backend",
    "version": "0.0.1-SNAPSHOT"
  },
  "message": "처리 완료",
  "error": null
}
```
