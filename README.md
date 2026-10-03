# rami-art-backend

This application is maintained in the monorepo's `backend/` directory. It was
imported from `donghyunlee-dev/rami-art-backend` at commit
`c27ca15452286edecdac2653ab8270b32131240c` (2026-10-03).
The deployment and environment guide is [`../docs/backend/MONOREPO-DEPLOYMENT.md`](../docs/backend/MONOREPO-DEPLOYMENT.md).

Rami Art Studio API backend built with Spring Boot. The application now serves
the existing public API and the administrator API from one Render service.

## Requirements

- Java 21
- Gradle (or use `./gradlew`)

## Run

```bash
./gradlew bootRun
```

From the monorepo root, use `bun run backend:dev`, `bun run backend:build`, or
`bun run backend:test`. The development wrapper loads the ignored root
`.env.backend.dev.local` when present, otherwise `.env.backend.local`;
see `.env.backend.local.example` for required names.
Build and test commands do not load development database credentials.

`bun run backend:local-db` starts disposable PostgreSQL on port 54322 and applies
the root administrator migrations and local seed. The local-only account is
`owner@rami.local`, with temporary password `LocalOnly!Change123`; change it on
first login. Never apply this seed to production.

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
RLS, grants and seed files are versioned under root `../supabase/migrations`
and `../supabase/seed.sql` and must
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
