# 2026-03-11 Public Site Seed Plan

## Context
- Request: seed the current public content from `https://www.ramiartstudio.com` into the existing Supabase PostgreSQL database.
- Source pages used for this seed:
  - `/`
  - `/classes`
  - `/about`
- Scope is limited to text and metadata already represented by the current schema.

## Target Tables
- `rami_art_studio.site_settings`
- `rami_art_studio.page_sections`
- `rami_art_studio.classes`
- `rami_art_studio.class_tags`

## Mapping Strategy
- `site_settings`: phone, email, address, business hours, parking, SEO title and description.
- `page_sections`: homepage hero/features and about page intro/philosophy/teacher/facility content.
- `classes`: kindergarten, elementary, and middle class summary and curriculum text.
- `class_tags`: curriculum keywords derived from each class section.

## Change Method
- Add a Flyway migration so the seed is deterministic and reproducible in local, test, and deployed environments.
- Use fixed UUIDs and conflict-safe upserts where possible.

## Validation
- Run migration against Supabase PostgreSQL using the existing `dev` profile.
- Query seeded rows directly from PostgreSQL to confirm record counts and key field values.
- Run `./gradlew test` to confirm the project still boots and migrations remain valid.
