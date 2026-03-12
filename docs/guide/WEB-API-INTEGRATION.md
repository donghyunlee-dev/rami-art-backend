# Web API Integration Guide

> Frontend(Next.js) 연동 기준 문서. 이 문서는 현재 서버에 실제 구현되어 있고 테스트로 검증된 API만 포함한다.
>
> Last updated: `2026-03-12`

## 1) Base Rules
- Base URL (Local): `http://localhost:9000/api`
- Base URL (Prod): `https://rami-art-backend.onrender.com/api`
- Content-Type: `application/json`
- Auth Header: `Authorization: Bearer <accessToken>`
- JSON Naming: `camelCase`
- Time Format: ISO 8601 (`2026-03-11T00:00:00Z`)

## 2) Response Envelope

### Success
```json
{
  "success": true,
  "data": {},
  "message": "처리 완료",
  "error": null
}
```

### Error
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

## 3) Auth Policy
- Public APIs
  - `/api/v1/system/**`
  - `/api/v1/auth/login`
  - `/api/v1/auth/refresh`
  - `/api/v1/classes/**`
- Protected APIs
  - `/api/v1/auth/logout`
  - `/api/v1/auth/me`
  - `/api/v1/auth/password`
  - `/api/v1/admin/classes/**`
- 로그인 성공 시 `accessToken`, `refreshToken`을 함께 받는다.
- `401 Unauthorized` 응답 시 `POST /api/v1/auth/refresh`로 토큰 재발급 후 원요청을 재시도한다.
- 비밀번호 변경 성공 시 기존 refresh token은 모두 무효화된다. 재로그인 처리가 안전하다.

## 4) Implemented APIs

### GET `/api/v1/system/health`
- Auth: No
- Response 200
```json
{
  "success": true,
  "data": {
    "status": "UP",
    "service": "rami-art-studio-api",
    "timestamp": "2026-03-12T13:40:10.123Z"
  },
  "message": "처리 완료",
  "error": null
}
```

### GET `/api/v1/system/version`
- Auth: No
- Response 200
```json
{
  "success": true,
  "data": {
    "service": "rami-art-studio-api",
    "version": "local"
  },
  "message": "처리 완료",
  "error": null
}
```

### POST `/api/v1/auth/login`
- Auth: No
- Request Body
```json
{
  "email": "admin@ramiartstudio.com",
  "password": "ChangeMe123!"
}
```
- Response 200
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "uuid-uuiduuid",
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
- Auth: No
- Request Body
```json
{
  "refreshToken": "uuid-uuiduuid"
}
```
- Response 200
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "uuid-uuiduuid",
    "expiresIn": 3600
  },
  "message": "처리 완료",
  "error": null
}
```

### POST `/api/v1/auth/logout`
- Auth: Yes
- Response 200
```json
{
  "success": true,
  "data": null,
  "message": "로그아웃 되었습니다.",
  "error": null
}
```

### GET `/api/v1/auth/me`
- Auth: Yes
- Response 200
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
- Auth: Yes
- Request Body
```json
{
  "currentPassword": "ChangeMe123!",
  "newPassword": "ChangeMe123!2"
}
```
- Response 200
```json
{
  "success": true,
  "data": null,
  "message": "비밀번호가 변경되었습니다.",
  "error": null
}
```

### GET `/api/v1/classes`
- Auth: No
- Description: 활성화된 수업만 정렬 순서 기준으로 반환
- Response 200
```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "ageGroup": "kindergarten",
      "ageRange": "5-7세",
      "title": "유치부 미술 수업",
      "description": "수업 설명",
      "curriculum": "커리큘럼 설명",
      "thumbnailUrl": "/images/design-mode/Gallery_08.png",
      "sortOrder": 1,
      "isActive": true,
      "tags": ["기초 드로잉 표현", "선·형태·비율 연습"],
      "createdAt": "2026-03-11T00:00:00Z",
      "updatedAt": "2026-03-11T00:00:00Z"
    }
  ],
  "message": "처리 완료",
  "error": null
}
```

### GET `/api/v1/classes/{classId}`
- Auth: No
- Description: 활성화된 수업만 조회 가능
- Response 200
  - `GET /api/v1/classes`의 단건 구조와 동일

### GET `/api/v1/admin/classes`
- Auth: Yes
- Description: 비활성 수업 포함, soft delete 된 데이터 제외
- Response 200
  - `GET /api/v1/classes`와 동일한 배열 구조

### POST `/api/v1/admin/classes`
- Auth: Yes
- Request Body
```json
{
  "ageGroup": "middle",
  "ageRange": "14-16세",
  "title": "중등부 심화 수업",
  "description": "수업 설명",
  "curriculum": "커리큘럼 설명",
  "thumbnailUrl": "https://example.com/class.jpg",
  "sortOrder": 3,
  "isActive": true,
  "tags": ["표현 기법 심화", "개인 작품 제작"]
}
```
- Validation
  - `ageGroup`: `kindergarten` | `elementary` | `middle`
  - `tags`: 최소 1개 이상
- Response 201
  - 단건 수업 구조 반환

### PUT `/api/v1/admin/classes/{classId}`
- Auth: Yes
- Request Body
  - `POST /api/v1/admin/classes`와 동일
- Response 200
  - 단건 수업 구조 반환

### PATCH `/api/v1/admin/classes/{classId}/sort`
- Auth: Yes
- Request Body
```json
{
  "sortOrder": 10
}
```
- Response 200
  - 단건 수업 구조 반환

### DELETE `/api/v1/admin/classes/{classId}`
- Auth: Yes
- Description: soft delete
- Response 200
```json
{
  "success": true,
  "data": null,
  "message": "수업이 삭제되었습니다.",
  "error": null
}
```

### GET `/api/v1/gallery/categories`
- Auth: No
- Description: 활성화된 갤러리 카테고리 목록 반환
- Response 200
```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "name": "수채화",
      "slug": "watercolor",
      "sortOrder": 1,
      "isActive": true,
      "createdAt": "2026-03-12T00:00:00Z"
    }
  ],
  "message": "처리 완료",
  "error": null
}
```

### GET `/api/v1/gallery/works`
- Auth: No
- Query Params
  - `categoryId`: optional UUID
  - `ageGroup`: optional `kindergarten|elementary|middle`
  - `featured`: optional boolean
  - `keyword`: optional string, `title`/`artistName` 대상
  - `page`: default `0`
  - `size`: default `20`
- Response 200
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "uuid",
        "title": "봄 풍경 수채화",
        "description": "초등부 학생이 완성한 봄 풍경 수채화 작품입니다.",
        "imageUrl": "https://example.com/gallery/watercolor-spring.jpg",
        "imagePath": "gallery/2026/watercolor-spring.jpg",
        "ageGroup": "elementary",
        "artistName": "김하늘",
        "createdYear": 2026,
        "sortOrder": 1,
        "isFeatured": true,
        "isActive": true,
        "category": {
          "id": "uuid",
          "name": "수채화",
          "slug": "watercolor"
        },
        "createdAt": "2026-03-12T00:00:00Z",
        "updatedAt": "2026-03-12T00:00:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "number": 0,
    "size": 20
  },
  "message": "처리 완료",
  "error": null
}
```

### GET `/api/v1/gallery/works/{workId}`
- Auth: No
- Description: 활성화된 작품만 조회 가능

### GET `/api/v1/admin/gallery/categories`
- Auth: Yes
- Description: 활성/비활성 포함 전체 카테고리 목록 반환

### POST `/api/v1/admin/gallery/categories`
- Auth: Yes
- Request Body
```json
{
  "name": "수채화",
  "slug": "watercolor",
  "sortOrder": 1,
  "isActive": true
}
```

### PUT `/api/v1/admin/gallery/categories/{categoryId}`
- Auth: Yes
- Request Body
  - `POST /api/v1/admin/gallery/categories`와 동일

### DELETE `/api/v1/admin/gallery/categories/{categoryId}`
- Auth: Yes

### GET `/api/v1/admin/gallery/works`
- Auth: Yes
- Query Params
  - `categoryId`: optional UUID
  - `ageGroup`: optional `kindergarten|elementary|middle`
  - `featured`: optional boolean
  - `isActive`: optional boolean
  - `keyword`: optional string
  - `page`: default `0`
  - `size`: default `20`

### POST `/api/v1/admin/gallery/works`
- Auth: Yes
- Content-Type: `multipart/form-data`
- Form Fields
  - `image`: required file, `image/jpeg | image/png | image/webp`, max `10MB`
  - `categoryId`: optional UUID
  - `title`: required string
  - `description`: optional string
  - `ageGroup`: optional `kindergarten|elementary|middle`
  - `artistName`: optional string
  - `createdYear`: optional number
  - `sortOrder`: required number
  - `isFeatured`: required boolean
  - `isActive`: required boolean
- Response
  - 업로드 성공 후 서버가 Supabase Storage bucket `pub_rami_art_bucket`에 이미지를 저장하고, 응답의 `imageUrl`, `imagePath`를 채운다.

### PUT `/api/v1/admin/gallery/works/{workId}`
- Auth: Yes
- Content-Type: `multipart/form-data`
- Form Fields
  - `POST /api/v1/admin/gallery/works`와 동일
  - `image`: optional file
- Description
  - 새 `image`가 포함되면 기존 Storage 파일을 교체한다.

### PATCH `/api/v1/admin/gallery/works/{workId}/sort`
- Auth: Yes
- Request Body
```json
{
  "sortOrder": 10
}
```

### DELETE `/api/v1/admin/gallery/works/{workId}`
- Auth: Yes
- Description: soft delete + Storage 파일 삭제

## 5) Common Errors

### 400 Validation Error
```json
{
  "success": false,
  "data": null,
  "message": null,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "ageGroup: must match \"kindergarten|elementary|middle\""
  }
}
```

### 401 Unauthorized
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

### 403 Forbidden
```json
{
  "success": false,
  "data": null,
  "message": null,
  "error": {
    "code": "FORBIDDEN",
    "message": "접근 권한이 없습니다."
  }
}
```

### 404 Not Found
```json
{
  "success": false,
  "data": null,
  "message": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "수업 정보를 찾을 수 없습니다."
  }
}
```

### 409 Conflict
```json
{
  "success": false,
  "data": null,
  "message": null,
  "error": {
    "code": "CONFLICT",
    "message": "같은 연령 그룹의 수업이 이미 존재합니다."
  }
}
```

## 6) Not Yet Implemented
- Blog API
- Contact API
- Settings API

## 7) Required Env Vars
- `SUPABASE_URL`
- `SUPABASE_SERVICE_KEY`
- `SUPABASE_GALLERY_BUCKET`
  - default: `pub_rami_art_bucket`
