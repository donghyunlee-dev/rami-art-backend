# Web API Integration Guide

> 웹(Next.js) 연동용 실무 문서. 각 개발 단계 완료 시 최신 상태로 갱신한다.

## 1) Base Rules
- Base URL (Local): `http://localhost:9000/api`
- Base URL (Prod): `https://rami-art-backend.onrender.com/api`
- Content-Type: `application/json`
- Auth Header: `Authorization: Bearer <access_token>`
- JSON Naming: 배포 환경 기준 `snake_case` 사용
- Response Envelope:
  - Success: `{ "success": true, "data": ..., "message": "처리 완료", "error": null }`
  - Error: `{ "success": false, "data": null, "message": null, "error": { "code": "...", "message": "..." } }`

## 2) Auth Policy (Web)
- 로그인 성공 시 `access_token`, `refresh_token` 저장
- 권장 저장 방식:
  - `access_token`: 메모리
  - `refresh_token`: HttpOnly 쿠키 또는 안전한 저장소
- 401 처리 흐름:
  1. 보호 API 호출 실패
  2. `POST /api/v1/auth/refresh` 호출
  3. 새 `access_token`으로 재시도

## 3) Implemented Endpoints

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
    "access_token": "eyJ...",
    "refresh_token": "uuid-uuid",
    "expires_in": 3600,
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
  "refresh_token": "uuid-uuid"
}
```

### POST `/api/v1/auth/logout`
- Auth: 필요

### GET `/api/v1/auth/me`
- Auth: 필요

### PATCH `/api/v1/auth/password`
- Auth: 필요
- Request Body:
```json
{
  "current_password": "ChangeMe123!",
  "new_password": "ChangeMe123!2"
}
```

### GET `/api/v1/system/health`
- Auth: 불필요

### GET `/api/v1/system/version`
- Auth: 불필요

### GET `/api/v1/classes`
- Auth: 불필요
- Response 200:
```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "age_group": "kindergarten",
      "age_range": "5-7세",
      "title": "유치부 미술 수업",
      "description": "아동의 발달 단계에 맞춘 드로잉 중심 미술 수업...",
      "curriculum": "다양한 도구와 재료를 활용한 기초 드로잉 표현\n...",
      "thumbnail_url": "/images/design-mode/Gallery_08.png",
      "sort_order": 1,
      "is_active": true,
      "tags": ["기초 드로잉 표현", "선·형태·비율 연습"],
      "created_at": "2026-03-11T00:00:00Z",
      "updated_at": "2026-03-11T00:00:00Z"
    }
  ],
  "message": "처리 완료",
  "error": null
}
```

### GET `/api/v1/classes/{class_id}`
- Auth: 불필요
- Response: `GET /api/v1/classes`의 단건 구조와 동일

### GET `/api/v1/admin/classes`
- Auth: 필요
- 설명: 비활성 수업 포함, `deleted_at` 제외 전체 목록 조회

### POST `/api/v1/admin/classes`
- Auth: 필요
- Request Body:
```json
{
  "age_group": "middle",
  "age_range": "14-16세",
  "title": "중등부 심화 수업",
  "description": "수업 설명",
  "curriculum": "커리큘럼 설명",
  "thumbnail_url": "https://example.com/class.jpg",
  "sort_order": 3,
  "is_active": true,
  "tags": ["표현 기법 심화", "개인 작품 제작"]
}
```
- Response 201: 단건 수업 구조 반환

### PUT `/api/v1/admin/classes/{class_id}`
- Auth: 필요
- Request Body: `POST /api/v1/admin/classes`와 동일

### PATCH `/api/v1/admin/classes/{class_id}/sort`
- Auth: 필요
- Request Body:
```json
{
  "sort_order": 10
}
```

### DELETE `/api/v1/admin/classes/{class_id}`
- Auth: 필요
- Response 200:
```json
{
  "success": true,
  "data": null,
  "message": "수업이 삭제되었습니다.",
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
- Phase 2 완료: Auth API 5종(login/refresh/logout/me/password) 반영
- Phase 3 완료: Classes 공개/관리자 API 반영
