# Web API Integration Guide

> 웹(Next.js) 연동용 실무 문서. 각 개발 단계 완료 시 이 문서를 최신 상태로 갱신한다.

## 1) Base Rules
- Base URL (Local): `http://localhost:9000/api`
- Base URL (Prod): `https://api.ramiartstudio.com/api`
- Content-Type: `application/json`
- Response Envelope:
  - Success: `{ "success": true, "data": ..., "message": "처리 완료", "error": null }`
  - Error: `{ "success": false, "data": null, "message": null, "error": { "code": "...", "message": "..." } }`

## 2) Auth Policy (Web)
- 인증 방식: `Authorization: Bearer <accessToken>`
- 토큰 저장 권장:
  - Access Token: 메모리(권장) 또는 보안 저장소
  - Refresh Token: HttpOnly 쿠키(권장)
- 재발급 흐름:
  1. API 401 응답 수신
  2. `POST /api/v1/auth/refresh` 호출
  3. 새 access token으로 원 요청 재시도

## 3) Endpoint Contract (Implemented)

### POST `/api/v1/auth/login`
- Auth: 불필요
- Request Body:
```json
{
  "email": "admin@ramiartstudio.com",
  "password": "ChangeMe123!"
}
```
- Response 200:
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "uuid-uuid",
    "expiresIn": 3600,
    "admin": {
      "id": "uuid",
      "name": "원장",
      "role": "admin",
      "email": "admin@ramiartstudio.com"
    }
  },
  "message": "처리 완료",
  "error": null
}
```

### POST `/api/v1/auth/refresh`
- Auth: 불필요
- Request Body:
```json
{
  "refreshToken": "uuid-uuid"
}
```
- Response 200:
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "uuid-uuid",
    "expiresIn": 3600
  },
  "message": "처리 완료",
  "error": null
}
```

### POST `/api/v1/auth/logout`
- Auth: 필요
- Request Body: 없음
- Response 200:
```json
{
  "success": true,
  "data": null,
  "message": "로그아웃 되었습니다.",
  "error": null
}
```

### GET `/api/v1/auth/me`
- Auth: 필요
- Response 200:
```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "name": "원장",
    "role": "admin",
    "email": "admin@ramiartstudio.com"
  },
  "message": "처리 완료",
  "error": null
}
```

### PATCH `/api/v1/auth/password`
- Auth: 필요
- Request Body:
```json
{
  "currentPassword": "ChangeMe123!",
  "newPassword": "ChangeMe123!2"
}
```
- Response 200:
```json
{
  "success": true,
  "data": null,
  "message": "비밀번호가 변경되었습니다.",
  "error": null
}
```

### GET `/api/v1/system/health`
- Auth: 불필요
- Request Body: 없음
- Response 200 예시:
```json
{
  "success": true,
  "data": {
    "status": "UP",
    "service": "rami-art-studio-api",
    "timestamp": "2026-03-08T14:00:00Z"
  },
  "message": "처리 완료",
  "error": null
}
```

### GET `/api/v1/system/version`
- Auth: 불필요
- Request Body: 없음
- Response 200 예시:
```json
{
  "success": true,
  "data": {
    "service": "rami-art-studio-api",
    "version": "0.0.1-SNAPSHOT"
  },
  "message": "처리 완료",
  "error": null
}
```

### 공통 인증 오류 응답 (401)
```json
{
  "success": false,
  "data": null,
  "message": null,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "인증이 필요합니다."
  }
}
```

## 4) Update Log (Phase-based)
- Phase 1 완료: 공통 응답 규격 + system API 반영
- Phase 2 완료: Auth API 5종(login/refresh/logout/me/password) 요청/응답 JSON 반영
