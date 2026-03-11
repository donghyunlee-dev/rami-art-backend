# 🔌 라미아트미술교습소 — API Specification

> **버전** v1.0 | **작성일** 2026-03-08 | **Backend** Java 21 Spring Boot 3

---

## 📐 API 공통 규격

| 항목 | 내용 |
|------|------|
| Base URL (Local) | `http://localhost:9000/api` |
| Base URL (Production) | `https://rami-art-backend.onrender.com/api` |
| 프로토콜 | HTTPS 강제 (HTTP → HTTPS 리다이렉트) |
| Content-Type | `application/json` (파일 업로드: `multipart/form-data`) |
| 인증 방식 | Bearer Token (JWT) — `Authorization: Bearer {token}` |
| 응답 인코딩 | UTF-8 |
| API 버전 | URL 경로 방식 (`/api/v1/...`) |
| 날짜 형식 | ISO 8601 (`2026-03-08T14:00:00Z`) |
| 페이지네이션 | Query Params: `page=0&size=20&sort=createdAt,desc` |

---

### 공통 응답 포맷

**✅ 성공 응답**

```json
{
  "success": true,
  "data": { ... },
  "message": "처리 완료"
}
```

**✅ 페이지네이션 응답**

```json
{
  "success": true,
  "data": {
    "content": [...],
    "totalElements": 50,
    "totalPages": 3,
    "number": 0,
    "size": 20
  }
}
```

**❌ 오류 응답**

```json
{
  "success": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "리소스를 찾을 수 없습니다."
  }
}
```

---

### HTTP 상태 코드

| 코드 | 의미 | 사용 케이스 |
|------|------|-------------|
| `200 OK` | 성공 | 조회, 수정, 삭제 성공 |
| `201 Created` | 생성 성공 | 리소스 생성 완료 |
| `400 Bad Request` | 잘못된 요청 | 유효성 검사 실패, 잘못된 파라미터 |
| `401 Unauthorized` | 인증 실패 | 토큰 없음 또는 만료 |
| `403 Forbidden` | 권한 없음 | 관리자 전용 엔드포인트 접근 |
| `404 Not Found` | 리소스 없음 | 존재하지 않는 리소스 |
| `409 Conflict` | 중복 충돌 | slug 중복 등 |
| `500 Internal Server Error` | 서버 오류 | 예기치 못한 서버 오류 |

---

## 🔐 인증 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `POST` | `/api/v1/auth/login` | 관리자 로그인 | ❌ |
| `POST` | `/api/v1/auth/logout` | 로그아웃 | ✅ |
| `POST` | `/api/v1/auth/refresh` | 토큰 갱신 | ❌ |
| `GET` | `/api/v1/auth/me` | 현재 로그인 정보 조회 | ✅ |
| `PATCH` | `/api/v1/auth/password` | 비밀번호 변경 | ✅ |

---

### POST `/api/v1/auth/login`

**Request Body**

```json
{
  "email": "admin@ramiartstudio.com",
  "password": "your_password"
}
```

**Response 200**

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "expiresIn": 3600,
    "admin": {
      "id": "uuid",
      "name": "원장",
      "role": "admin"
    }
  }
}
```

---

### POST `/api/v1/auth/refresh`

**Request Body**

```json
{
  "refreshToken": "eyJ..."
}
```

**Response 200**

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJ...",
    "expiresIn": 3600
  }
}
```

---

### PATCH `/api/v1/auth/password`

**Request Body**

```json
{
  "currentPassword": "old_password",
  "newPassword": "new_password"
}
```

---

## 🎨 수업 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/classes` | 수업 목록 조회 (is_active=true) | ❌ |
| `GET` | `/api/v1/classes/{id}` | 수업 상세 조회 | ❌ |
| `GET` | `/api/v1/admin/classes` | 전체 수업 목록 (비활성 포함) | ✅ |
| `POST` | `/api/v1/admin/classes` | 수업 생성 | ✅ |
| `PUT` | `/api/v1/admin/classes/{id}` | 수업 수정 | ✅ |
| `DELETE` | `/api/v1/admin/classes/{id}` | 수업 삭제 (소프트) | ✅ |
| `PATCH` | `/api/v1/admin/classes/{id}/sort` | 순서 변경 | ✅ |

---

### GET `/api/v1/classes`

**Response 200**

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "ageGroup": "kindergarten",
      "ageRange": "5-7세",
      "title": "유치부 미술 수업",
      "description": "수업 설명...",
      "curriculum": "커리큘럼 상세...",
      "thumbnailUrl": "/images/design-mode/Gallery_08.png",
      "sortOrder": 1,
      "isActive": true,
      "tags": ["기초 드로잉 표현", "선·형태·비율 연습"],
      "createdAt": "2026-03-11T00:00:00Z",
      "updatedAt": "2026-03-11T00:00:00Z"
    }
  ]
}
```

### GET `/api/v1/classes/{id}`

**Response 200**

- `GET /api/v1/classes`의 단건 구조와 동일

### GET `/api/v1/admin/classes`

**Response 200**

- `GET /api/v1/classes`와 동일한 배열 구조, 단 비활성 수업 포함

### POST `/api/v1/admin/classes`

**Request Body**

```json
{
  "ageGroup": "kindergarten",
  "ageRange": "5-7세",
  "title": "유치부 미술 수업",
  "description": "수업 설명...",
  "curriculum": "커리큘럼 상세...",
  "thumbnailUrl": "https://...",
  "sortOrder": 1,
  "isActive": true,
  "tags": ["기초 드로잉 표현", "선·형태·비율 연습"]
}
```

> `ageGroup` 허용값: `kindergarten` | `elementary` | `middle`

**Response 201**

```json
{
  "success": true,
  "data": {
    "id": "uuid",
    "ageGroup": "kindergarten",
    "ageRange": "5-7세",
    "title": "유치부 미술 수업",
    "description": "수업 설명...",
    "curriculum": "커리큘럼 상세...",
    "thumbnailUrl": "https://...",
    "sortOrder": 1,
    "isActive": true,
    "tags": ["기초 드로잉 표현", "선·형태·비율 연습"],
    "createdAt": "2026-03-11T00:00:00Z",
    "updatedAt": "2026-03-11T00:00:00Z"
  }
}
```

### PUT `/api/v1/admin/classes/{id}`

**Request Body**

- `POST /api/v1/admin/classes`와 동일

### PATCH `/api/v1/admin/classes/{id}/sort`

**Request Body**

```json
{
  "sortOrder": 10
}
```

### DELETE `/api/v1/admin/classes/{id}`

**Response 200**

```json
{
  "success": true,
  "data": null,
  "message": "수업이 삭제되었습니다."
}
```

---

## 🖼 갤러리 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/gallery/categories` | 카테고리 목록 | ❌ |
| `GET` | `/api/v1/gallery/works` | 작품 목록 | ❌ |
| `GET` | `/api/v1/gallery/works/{id}` | 작품 상세 | ❌ |
| `GET` | `/api/v1/admin/gallery/works` | 전체 작품 목록 | ✅ |
| `POST` | `/api/v1/admin/gallery/works` | 작품 등록 | ✅ |
| `PUT` | `/api/v1/admin/gallery/works/{id}` | 작품 수정 | ✅ |
| `DELETE` | `/api/v1/admin/gallery/works/{id}` | 작품 삭제 | ✅ |
| `PATCH` | `/api/v1/admin/gallery/works/sort` | 일괄 순서 변경 | ✅ |
| `POST` | `/api/v1/admin/gallery/categories` | 카테고리 생성 | ✅ |
| `PUT` | `/api/v1/admin/gallery/categories/{id}` | 카테고리 수정 | ✅ |
| `DELETE` | `/api/v1/admin/gallery/categories/{id}` | 카테고리 삭제 | ✅ |

---

### GET `/api/v1/gallery/works` — Query Parameters

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `categoryId` | UUID | ❌ | 카테고리 필터 |
| `ageGroup` | string | ❌ | 연령대 필터 |
| `featured` | boolean | ❌ | 홈 미리보기 전용 |
| `page` | integer | ❌ | 페이지 번호 (default: 0) |
| `size` | integer | ❌ | 페이지 크기 (default: 20) |

---

### POST `/api/v1/admin/gallery/works` — `multipart/form-data`

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `image` | file | ✅ | 이미지 파일 (JPEG/PNG/WebP, max 10MB) |
| `title` | string | ✅ | 작품명 |
| `description` | string | ❌ | 작품 설명 |
| `categoryId` | UUID | ❌ | 카테고리 ID |
| `ageGroup` | string | ❌ | 연령대 |
| `artistName` | string | ❌ | 작가명 |
| `createdYear` | integer | ❌ | 제작 연도 |
| `isFeatured` | boolean | ❌ | 홈 미리보기 노출 여부 |
| `sortOrder` | integer | ❌ | 전시 순서 |

---

### PATCH `/api/v1/admin/gallery/works/sort`

**Request Body**

```json
{
  "orders": [
    { "id": "uuid-1", "sortOrder": 1 },
    { "id": "uuid-2", "sortOrder": 2 },
    { "id": "uuid-3", "sortOrder": 3 }
  ]
}
```

---

## 📝 블로그 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/blog/posts` | 발행 게시글 목록 | ❌ |
| `GET` | `/api/v1/blog/posts/{slug}` | 게시글 상세 (slug 기반) | ❌ |
| `GET` | `/api/v1/admin/blog/posts` | 전체 게시글 (초안 포함) | ✅ |
| `POST` | `/api/v1/admin/blog/posts` | 게시글 작성 | ✅ |
| `PUT` | `/api/v1/admin/blog/posts/{id}` | 게시글 수정 | ✅ |
| `DELETE` | `/api/v1/admin/blog/posts/{id}` | 게시글 삭제 (소프트) | ✅ |
| `PATCH` | `/api/v1/admin/blog/posts/{id}/publish` | 발행/비발행 토글 | ✅ |
| `POST` | `/api/v1/admin/blog/images` | 본문 이미지 업로드 | ✅ |

---

### POST `/api/v1/admin/blog/posts`

**Request Body**

```json
{
  "title": "2026 여름 수업 안내",
  "content": "<p>HTML 본문...</p>",
  "excerpt": "요약 텍스트...",
  "thumbnailUrl": "https://...",
  "status": "draft",
  "publishedAt": "2026-06-01T09:00:00Z"
}
```

> `status` 허용값: `draft` | `published`
> `publishedAt`이 null이면 즉시 발행 시각 기록

---

### PATCH `/api/v1/admin/blog/posts/{id}/publish`

**Request Body**

```json
{
  "status": "published"
}
```

---

## 📬 문의 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `POST` | `/api/v1/contacts` | 문의 접수 (홈페이지 폼) | ❌ |
| `GET` | `/api/v1/admin/contacts` | 문의 목록 | ✅ |
| `GET` | `/api/v1/admin/contacts/{id}` | 문의 상세 | ✅ |
| `PATCH` | `/api/v1/admin/contacts/{id}/status` | 상태 변경 | ✅ |
| `PATCH` | `/api/v1/admin/contacts/{id}/memo` | 관리자 메모 저장 | ✅ |
| `DELETE` | `/api/v1/admin/contacts/{id}` | 문의 삭제 | ✅ |

---

### POST `/api/v1/contacts`

**Request Body**

```json
{
  "name": "홍길동",
  "phone": "010-1234-5678",
  "interestClass": "elementary",
  "message": "수업 문의 드립니다."
}
```

> `interestClass` 허용값: `kindergarten` | `elementary` | `middle`

---

### GET `/api/v1/admin/contacts` — Query Parameters

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `status` | string | `unread` \| `read` \| `replied` 필터 |
| `page` | integer | 페이지 번호 |
| `size` | integer | 페이지 크기 |

---

### PATCH `/api/v1/admin/contacts/{id}/status`

**Request Body**

```json
{
  "status": "replied"
}
```

> `status` 허용값: `unread` | `read` | `replied`

---

### PATCH `/api/v1/admin/contacts/{id}/memo`

**Request Body**

```json
{
  "memo": "전화 통화 완료, 체험 수업 예약됨"
}
```

---

## 📄 페이지 섹션 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/pages/{pageKey}/sections` | 페이지 섹션 콘텐츠 조회 | ❌ |
| `PUT` | `/api/v1/admin/pages/{pageKey}/sections/{sectionKey}` | 섹션 콘텐츠 수정 | ✅ |

---

### 페이지/섹션 키 목록

| pageKey | sectionKey | 설명 |
|---------|------------|------|
| `home` | `hero` | 히어로 배너 문구 및 서브타이틀 |
| `home` | `stats` | 통계 수치 (최대 인원, 과정 수, 경력) |
| `home` | `features` | 특별함 섹션 텍스트 (3개 카드) |
| `about` | `intro` | 교습소 소개 텍스트 |
| `about` | `teacher` | 선생님/원장 소개 |
| `about` | `philosophy` | 교육 철학 |
| `contact` | `location` | 위치 및 교통편 안내 |

---

### PUT `/api/v1/admin/pages/{pageKey}/sections/{sectionKey}`

**Request Body**

```json
{
  "title": "창의력이 피어나는 곳",
  "subtitle": "최대 5명의 소규모 수업으로 아이 한 명 한 명의 잠재력을 발견하고 키워갑니다.",
  "content": null,
  "extraData": {
    "maxStudents": 5,
    "programs": 3,
    "yearsOfExperience": 7
  }
}
```

---

## ⚙️ 사이트 설정 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `GET` | `/api/v1/settings` | 사이트 설정 조회 | ❌ |
| `PUT` | `/api/v1/admin/settings` | 사이트 설정 전체 수정 | ✅ |
| `PATCH` | `/api/v1/admin/settings` | 사이트 설정 부분 수정 | ✅ |

---

### PUT `/api/v1/admin/settings`

**Request Body**

```json
{
  "phone": "010-6858-3758",
  "email": "school579@naver.com",
  "address": "인천광역시 남동구 호구포로 920",
  "addressDetail": "1층 107호",
  "businessHours": {
    "weekday": "14:00-18:00",
    "saturday": "휴무",
    "sunday": "휴무",
    "holiday": "휴무"
  },
  "instagramUrl": "https://instagram.com/ramiartstudio",
  "parkingInfo": "상가 건물주차장 주차 가능 (2시간 무료)",
  "transitInfo": "인천 2호선 모래내시장역 3번 출구 도보 10분",
  "metaTitle": "라미아트미술교습소 | 소수정예 맞춤 미술 교육",
  "metaDescription": "인천 남동구 소수정예 미술 교습소"
}
```

---

## 🗃 파일 업로드 API

### 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| `POST` | `/api/v1/admin/upload/image` | 범용 이미지 업로드 | ✅ |
| `DELETE` | `/api/v1/admin/upload/image` | 이미지 삭제 | ✅ |

---

### POST `/api/v1/admin/upload/image` — `multipart/form-data`

| 필드명 | 타입 | 필수 | 설명 |
|--------|------|------|------|
| `file` | file | ✅ | 이미지 파일 (JPEG/PNG/WebP, max 10MB) |
| `bucket` | string | ✅ | 버킷명 (`gallery` / `blog` / `classes`) |
| `path` | string | ❌ | 저장 경로 prefix (없으면 자동 생성) |

**Response 201**

```json
{
  "success": true,
  "data": {
    "url": "https://xxx.supabase.co/storage/v1/object/public/gallery/2026/uuid.webp",
    "path": "gallery/2026/uuid.webp"
  }
}
```

---

### DELETE `/api/v1/admin/upload/image`

**Request Body**

```json
{
  "bucket": "gallery",
  "path": "gallery/2026/uuid.webp"
}
```

---

## 🏗 Spring Boot 패키지 구조

도메인 중심의 레이어드 아키텍처 (SOLID 원칙 준수)

```
com.ramiartstudio.api
├── auth/
│   ├── controller/AuthController.java
│   ├── service/AuthService.java
│   ├── dto/LoginRequest.java
│   ├── dto/TokenResponse.java
│   └── security/JwtTokenProvider.java
├── gallery/
│   ├── controller/GalleryController.java
│   ├── controller/AdminGalleryController.java
│   ├── service/GalleryService.java
│   ├── repository/GalleryWorkRepository.java
│   ├── domain/GalleryWork.java
│   ├── domain/GalleryCategory.java
│   ├── dto/GalleryWorkRequest.java
│   └── dto/GalleryWorkResponse.java
├── blog/
│   └── (동일 패턴)
├── classes/
│   └── (동일 패턴)
├── contact/
│   └── (동일 패턴)
├── settings/
│   └── (동일 패턴)
├── upload/
│   ├── controller/UploadController.java
│   └── service/SupabaseStorageService.java
└── common/
    ├── config/SecurityConfig.java
    ├── config/CorsConfig.java
    ├── response/ApiResponse.java
    └── exception/GlobalExceptionHandler.java
```

---

## 🔒 보안 설계

| 항목 | 적용 방식 |
|------|-----------|
| JWT Secret | 환경변수 관리 (`application.yml`에 하드코딩 금지) |
| Access Token 만료 | 1시간 |
| Refresh Token 만료 | 30일 (Supabase 관리) |
| CORS | 허용 Origin: `ramiartstudio.com`, `localhost:3000` |
| Rate Limiting | 문의 API: 분당 5회 제한 (Bucket4j) |
| 파일 검증 | MIME Type + Magic Bytes 이중 검증 |
| SQL Injection | JPA Parameterized Query 사용 (직접 SQL 금지) |
| XSS | 블로그 본문: DOMPurify sanitize 처리 |
| CSRF | SPA 구조 + JWT Bearer로 CSRF 위험 없음 |

---

## 📋 Gradle 의존성 (build.gradle)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // JWT
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'

    // PostgreSQL
    runtimeOnly 'org.postgresql:postgresql'

    // Rate Limiting
    implementation 'com.github.vladimir-bukhtoyarov:bucket4j-core:8.10.1'

    // QueryDSL
    implementation 'com.querydsl:querydsl-jpa:5.1.0:jakarta'
    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
}
```

---

> © 2026 라미아트미술교습소 API Spec v1.0