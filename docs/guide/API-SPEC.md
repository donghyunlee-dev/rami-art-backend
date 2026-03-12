# 라미아트미술교습소 API Specification

> 버전 `v1.1`
>
> 작성일 `2026-03-12`
>
> 이 문서는 현재 백엔드 코드에 실제 구현된 API만 기준으로 유지한다. Gallery, Blog, Contact, Settings 관련 API는 아직 미구현이며 본 문서 범위에서 제외한다.

## API 공통 규격

| 항목 | 내용 |
|------|------|
| Base URL (Local) | `http://localhost:9000/api` |
| Base URL (Production) | `https://rami-art-backend.onrender.com/api` |
| Content-Type | `application/json` |
| 인증 방식 | Bearer Token (JWT) |
| 인증 헤더 | `Authorization: Bearer {accessToken}` |
| 응답 인코딩 | UTF-8 |
| API 버전 | `/api/v1/...` |
| 날짜 형식 | ISO 8601 |
| JSON 필드명 | `camelCase` |

## 공통 응답 포맷

### 성공 응답
```json
{
  "success": true,
  "data": {},
  "message": "처리 완료",
  "error": null
}
```

### 실패 응답
```json
{
  "success": false,
  "data": null,
  "message": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "리소스를 찾을 수 없습니다."
  }
}
```

## HTTP 상태 코드

| 코드 | 의미 | 사용 케이스 |
|------|------|-------------|
| `200 OK` | 성공 | 조회, 수정, 삭제 성공 |
| `201 Created` | 생성 성공 | 리소스 생성 완료 |
| `400 Bad Request` | 잘못된 요청 | 유효성 검사 실패, 잘못된 요청 본문 |
| `401 Unauthorized` | 인증 실패 | 토큰 없음, 만료, 인증 실패 |
| `403 Forbidden` | 권한 없음 | 보호 API 접근 거부 |
| `404 Not Found` | 리소스 없음 | 존재하지 않는 리소스 |
| `409 Conflict` | 중복 충돌 | 연령 그룹 중복 수업 생성/수정 |
| `500 Internal Server Error` | 서버 오류 | 예기치 못한 서버 오류 |

## 구현 상태

| 영역 | 상태 |
|------|------|
| System API | 구현 완료 |
| Auth API | 구현 완료 |
| Classes API | 구현 완료 |
| Gallery API | 구현 완료 |
| Blog API | 미구현 |
| Contact API | 미구현 |
| Settings API | 미구현 |

## 인증 API

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `POST` | `/api/v1/auth/login` | 관리자 로그인 | ❌ |
| `POST` | `/api/v1/auth/logout` | 로그아웃 | ✅ |
| `POST` | `/api/v1/auth/refresh` | 토큰 재발급 | ❌ |
| `GET` | `/api/v1/auth/me` | 현재 로그인 관리자 조회 | ✅ |
| `PATCH` | `/api/v1/auth/password` | 비밀번호 변경 | ✅ |

### POST `/api/v1/auth/login`

Request
```json
{
  "email": "admin@ramiartstudio.com",
  "password": "ChangeMe123!"
}
```

Response 200
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

Request
```json
{
  "refreshToken": "uuid-uuiduuid"
}
```

Response 200
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

Response 200
```json
{
  "success": true,
  "data": null,
  "message": "로그아웃 되었습니다.",
  "error": null
}
```

### GET `/api/v1/auth/me`

Response 200
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

Request
```json
{
  "currentPassword": "ChangeMe123!",
  "newPassword": "ChangeMe123!2"
}
```

Response 200
```json
{
  "success": true,
  "data": null,
  "message": "비밀번호가 변경되었습니다.",
  "error": null
}
```

## System API

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/system/health` | 헬스체크 | ❌ |
| `GET` | `/api/v1/system/version` | 버전 조회 | ❌ |

## Classes API

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/classes` | 활성 수업 목록 조회 | ❌ |
| `GET` | `/api/v1/classes/{id}` | 활성 수업 상세 조회 | ❌ |
| `GET` | `/api/v1/admin/classes` | 전체 수업 목록 조회 | ✅ |
| `POST` | `/api/v1/admin/classes` | 수업 생성 | ✅ |
| `PUT` | `/api/v1/admin/classes/{id}` | 수업 수정 | ✅ |
| `DELETE` | `/api/v1/admin/classes/{id}` | 수업 소프트 삭제 | ✅ |
| `PATCH` | `/api/v1/admin/classes/{id}/sort` | 정렬 순서 변경 | ✅ |

### 수업 응답 구조
```json
{
  "id": "uuid",
  "ageGroup": "kindergarten",
  "ageRange": "5-7세",
  "title": "유치부 미술 수업",
  "description": "수업 설명",
  "curriculum": "커리큘럼 설명",
  "thumbnailUrl": "https://example.com/class.jpg",
  "sortOrder": 1,
  "isActive": true,
  "tags": ["기초 드로잉 표현", "선·형태·비율 연습"],
  "createdAt": "2026-03-11T00:00:00Z",
  "updatedAt": "2026-03-11T00:00:00Z"
}
```

### 수업 생성/수정 요청 구조
```json
{
  "ageGroup": "kindergarten",
  "ageRange": "5-7세",
  "title": "유치부 미술 수업",
  "description": "수업 설명",
  "curriculum": "커리큘럼 설명",
  "thumbnailUrl": "https://example.com/class.jpg",
  "sortOrder": 1,
  "isActive": true,
  "tags": ["기초 드로잉 표현", "선·형태·비율 연습"]
}
```

### 수업 생성/수정 검증 규칙
- `ageGroup` 허용값: `kindergarten`, `elementary`, `middle`
- `ageRange`: 최대 50자
- `title`: 최대 100자
- `sortOrder`: `0` 이상 `9999` 이하
- `isActive`: 필수
- `tags`: 최소 1개 이상, 각 태그 최대 100자
- 같은 `ageGroup`의 삭제되지 않은 수업은 1개만 허용

### PATCH `/api/v1/admin/classes/{id}/sort`

Request
```json
{
  "sortOrder": 10
}
```

## Gallery API

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/gallery/categories` | 활성 카테고리 목록 조회 | ❌ |
| `GET` | `/api/v1/gallery/works` | 활성 작품 목록 조회 | ❌ |
| `GET` | `/api/v1/gallery/works/{id}` | 활성 작품 상세 조회 | ❌ |
| `GET` | `/api/v1/admin/gallery/categories` | 전체 카테고리 목록 조회 | ✅ |
| `POST` | `/api/v1/admin/gallery/categories` | 카테고리 생성 | ✅ |
| `PUT` | `/api/v1/admin/gallery/categories/{id}` | 카테고리 수정 | ✅ |
| `DELETE` | `/api/v1/admin/gallery/categories/{id}` | 카테고리 삭제 | ✅ |
| `GET` | `/api/v1/admin/gallery/works` | 전체 작품 목록 조회 | ✅ |
| `POST` | `/api/v1/admin/gallery/works` | 작품 생성 | ✅ |
| `PUT` | `/api/v1/admin/gallery/works/{id}` | 작품 수정 | ✅ |
| `DELETE` | `/api/v1/admin/gallery/works/{id}` | 작품 소프트 삭제 | ✅ |
| `PATCH` | `/api/v1/admin/gallery/works/{id}/sort` | 작품 정렬 순서 변경 | ✅ |

### 카테고리 응답 구조
```json
{
  "id": "uuid",
  "name": "수채화",
  "slug": "watercolor",
  "sortOrder": 1,
  "isActive": true,
  "createdAt": "2026-03-12T00:00:00Z"
}
```

### 작품 응답 구조
```json
{
  "id": "uuid",
  "title": "봄 풍경 수채화",
  "description": "작품 설명",
  "imageUrl": "https://example.com/gallery/work.jpg",
  "imagePath": "gallery/2026/work.jpg",
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
```

### 작품 생성/수정 요청 구조
- Content-Type: `multipart/form-data`
- Fields
  - `image`: create 필수, update 선택
  - `categoryId`: optional UUID
  - `title`: required string
  - `description`: optional string
  - `ageGroup`: optional string
  - `artistName`: optional string
  - `createdYear`: optional integer
  - `sortOrder`: required integer
  - `isFeatured`: required boolean
  - `isActive`: required boolean

### 작품 목록 Query Parameters
- `categoryId`: optional UUID
- `ageGroup`: optional `kindergarten`, `elementary`, `middle`
- `featured`: optional boolean
- `keyword`: optional string
- `page`: default `0`
- `size`: default `20`

### 관리자 작품 목록 추가 Query Parameters
- `isActive`: optional boolean

### 카테고리 생성/수정 검증 규칙
- `name`: 최대 100자
- `slug`: 소문자/숫자/하이픈 패턴, 최대 100자
- `sortOrder`: `0` 이상 `9999` 이하
- `isActive`: 필수
- 같은 `slug`는 중복 허용하지 않음

### 작품 생성/수정 검증 규칙
- `title`: 최대 255자, 필수
- `image`: JPEG, PNG, WebP만 허용
- `image`: 최대 10MB
- `ageGroup`: 선택, 허용값 `kindergarten`, `elementary`, `middle`
- `artistName`: 최대 100자
- `createdYear`: `1900` 이상 `2100` 이하
- `sortOrder`: `0` 이상 `9999` 이하
- `isFeatured`: 필수
- `isActive`: 필수
- 업로드 성공 시 서버가 `pub_rami_art_bucket`에 파일을 저장하고 `imageUrl`, `imagePath`를 DB에 기록

### Storage 설정
- `SUPABASE_URL`: Supabase 프로젝트 URL
- `SUPABASE_SERVICE_KEY`: Storage 업로드/삭제용 service role key
- `SUPABASE_GALLERY_BUCKET`: optional, 기본값 `pub_rami_art_bucket`

### 페이지네이션 응답 구조
```json
{
  "content": [],
  "totalElements": 0,
  "totalPages": 0,
  "number": 0,
  "size": 20
}
```

## 공통 오류 코드

| Code | 의미 |
|------|------|
| `BAD_REQUEST` | 잘못된 요청 |
| `VALIDATION_ERROR` | 요청 검증 실패 |
| `NOT_FOUND` | 리소스 없음 |
| `UNAUTHORIZED` | 인증 필요 또는 인증 실패 |
| `FORBIDDEN` | 권한 없음 |
| `CONFLICT` | 중복 충돌 |
| `INTERNAL_SERVER_ERROR` | 서버 내부 오류 |
