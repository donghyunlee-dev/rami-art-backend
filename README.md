# rami-art-backend

Rami Art Studio API backend built with Spring Boot.

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
