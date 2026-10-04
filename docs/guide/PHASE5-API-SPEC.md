# 🧩 콘텐츠·문의·사이트 설정 API 명세

> 상태: 구현 계약 확정본
>
> 구현 상태: 구현 및 검증 진행 중 — 완료 상태는 `docs/result/`의 검증 기록을 따른다.
>
> 기준: 현재 백엔드 보안 필터·응답 envelope·Supabase migration과 웹 관리자 설계

## 계약 기준

Phase 5는 기존 시스템을 바꾸지 않고 관리자 전용 경계를 사용한다.

| 구분 | 경로 | 인증 | CSRF | 비고 |
|---|---|---|---|---|
| 기존 공개·기본 API | `/api/v1/**` | Bearer JWT | 비활성화 | 기존 `SecurityConfig` 유지 |
| 관리자·관리자 공개 API | `/api/admin/**`, `/api/public/**` | 관리자 API는 DB 세션 쿠키 | 비활성화 | `SecurityConfiguration`, `AdminSessionFilter`, `OriginValidationFilter` 유지 |

- 관리자 로그인은 `POST /api/admin/auth/sessions`가 `__Host-rami_admin_session` HttpOnly·Secure 쿠키를 발급한다. `SameSite`는 배포 형태에 따라 아래 표를 따른다.
- 관리자 세션은 서블릿 세션이 아니라 `admin_session` 저장소를 조회하는 DB-backed opaque token이다. Spring Security의 `STATELESS` 설정은 유지한다.
- 관리자 안전하지 않은 메서드(`POST`, `PUT`, `PATCH`, `DELETE`)는 `Origin`이 `admin.security.allowed-origin`과 같아야 한다. 현재 CSRF 토큰은 사용하지 않는다.
- 공개 문의 `POST /api/public/inquiries`는 인증 없이 허용되며 rate limit·honeypot·동의 검증을 적용한다.
- Phase 5 엔드포인트에는 Bearer JWT를 요구하지 않는다. `/api/v1/**`와 경로를 혼용하지 않는다.

### 현재 운영 연결 경계

브라우저는 same-origin `/api/backend/**` Next.js 프록시를 사용하며, 프록시가 Render 백엔드에 서버 간 요청을 전달한다. 따라서 브라우저가 Render API를 직접 호출하는 교차 출처 쿠키/CORS 시나리오는 현재 Phase 5 연동 기준이 아니다. 기존 세션 쿠키·Origin 검증은 현재 백엔드 구현 및 [`MONOREPO-DEPLOYMENT.md`](../../../docs/backend/MONOREPO-DEPLOYMENT.md)를 기준으로 유지하며, 외부 브라우저 도메인의 직접 호출은 별도 보안 계약과 배포 검증 없이 추가하지 않는다.

### 배포별 쿠키·CORS 결정 (직접 호출을 별도 도입할 경우에만 적용)

| 환경 | 웹 Origin | API Origin | 사이트 관계 | Cookie SameSite | Fetch credentials |
|---|---|---|---|---|---|
| 로컬 | `http://localhost:3000` | `http://localhost:9000` | same-site | `Strict` | `include` |
| 현재 운영 | `https://ramiartstudio.com` 또는 `https://rami-art-studio.vercel.app` | `https://rami-art-backend.onrender.com` | cross-site | `None` + `Secure` | `include` |
| 권장 향후 구성 | `https://ramiartstudio.com` | `https://api.ramiartstudio.com` | same-site | `Strict` 가능 | `include` |

아래 표와 CORS 규칙은 향후 브라우저가 Render 도메인을 직접 호출하도록 명시적으로 변경할 때에만 적용한다. 현재 프록시 기반 운영에서 이 설정을 도입하거나 보안 경계를 변경하지 않는다.

관리자 CORS는 현재 `/api/v1/**`용 `SecurityConfig`의 `allowCredentials=false` 설정과 분리한다.

- 관리자 `/api/admin/**`: `Access-Control-Allow-Origin`은 `ADMIN_ALLOWED_ORIGINS`에 등록된 정확한 Origin 하나만 반사하고, `Access-Control-Allow-Credentials: true`, `Vary: Origin`을 반환한다. `*`는 금지한다.
- 관리자 허용 Origin 기본 목록은 `https://ramiartstudio.com`, `https://rami-art-studio.vercel.app`이며 로컬 개발 시 `http://localhost:3000`을 별도로 추가한다.
- 허용 메서드는 `GET, HEAD, OPTIONS, POST, PUT, PATCH, DELETE`, 허용 헤더는 `Content-Type, Idempotency-Key, X-Request-Id`이며 `X-Request-Id`를 expose한다.
- 프론트엔드는 관리자 API 호출에 `credentials: "include"`를 사용하고, 공개 API 호출에는 `credentials: "omit"`를 사용한다.
- 공개 `/api/public/**` GET·POST는 인증 쿠키를 사용하지 않으므로 `Access-Control-Allow-Credentials: false`로 별도 허용한다. 공개 endpoint도 동일한 명시적 Origin 목록과 `Vary: Origin`을 사용한다.

## 공통 응답·오류

관리자 공통 응답은 현재 `ApiEnvelope` 구현과 동일하게 top-level `message`를 사용하지 않는다.

성공:

```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req_00000000-0000-0000-0000-000000000000"
}
```

실패:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "입력값을 확인해 주세요.",
    "fieldErrors": [
      {"field": "title", "code": "INVALID_VALUE", "message": "필수 값입니다."}
    ]
  },
  "requestId": "req_00000000-0000-0000-0000-000000000000"
}
```

모든 응답은 `X-Request-Id`를 헤더로 반환한다. 관리자 조회·변경 응답은 `Cache-Control: no-store`다.

공통 오류 매핑:

| HTTP | 코드 | 적용 |
|---:|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `INVALID_QUERY` | body·query 형식 오류 |
| 401 | `SESSION_REQUIRED`, `SESSION_EXPIRED`, `AUTHENTICATION_FAILED` | 세션 누락·만료·로그인 실패 |
| 403 | `ADMIN_ACCESS_DENIED`, `ORIGIN_NOT_ALLOWED` | 권한·Origin 실패 |
| 404 | `RESOURCE_NOT_FOUND` 및 리소스별 `*_NOT_FOUND` | 리소스 없음·삭제됨 |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `*_VERSION_CONFLICT`, `*_DRAFT_EXISTS` | 멱등·낙관적 잠금·초안 중복 |
| 422 | `*_INVALID`, `*_NOT_PUBLISHABLE`, `*_MEDIA_NOT_READY` | 도메인 검증 실패 |
| 429 | `AUTH_RATE_LIMITED`, `INQUIRY_RATE_LIMITED` | rate limit 초과 |
| 500 | `*_PERSISTENCE_FAILED`, `INTERNAL_SERVER_ERROR` | 저장소·예상하지 못한 오류 |

`fieldErrors`가 없는 오류는 빈 배열을 반환한다. 구현에 없는 오류 코드를 프론트엔드가 추측하지 않도록 아래 엔드포인트별 목록만 사용한다.

## 관리자 세션 계약

### 로그인

`POST /api/admin/auth/sessions`

인증: 없음 · 응답: `201 Created` · Origin 검사: 없음

요청:

```json
{"email":"admin@ramiartstudio.com","password":"...","returnUrl":"/admin/dashboard"}
```

응답 data:

```json
{
  "userId": "uuid",
  "displayName": "원장",
  "role": "admin",
  "passwordMustChange": false,
  "expiresAt": "2026-10-03T12:00:00Z",
  "redirectTo": "/admin/dashboard"
}
```

`Set-Cookie`에 세션 쿠키를 담는다. 로컬은 `SameSite=Strict`, 현재 운영은 `SameSite=None; Secure`를 사용한다. 오류는 `AUTHENTICATION_FAILED(401)`, `ACCOUNT_LOCKED(423)`, `AUTH_RATE_LIMITED(429)`, `VALIDATION_ERROR(400)`이다.

### 현재 세션·연장·로그아웃

- `GET /api/admin/auth/sessions/current`: 세션 필요, `200`; 현재 사용자·권한·만료 시각 반환
- `PATCH /api/admin/auth/sessions/current`: 세션 필요, body `{ "action": "EXTEND" }`, `200`; `SESSION_EXTENSION_INVALID(422)`, `SESSION_NOT_EXTENDABLE(409)`
- `DELETE /api/admin/auth/sessions/current`: 세션 필요, `204`; 쿠키 만료 처리

### 비밀번호 변경

`PUT /api/admin/users/me/password`

세션 필요 · Origin 필요 · body `{ "currentPassword": "...", "newPassword": "..." }`.

오류는 `CURRENT_PASSWORD_INVALID(401)`, `PASSWORD_POLICY_VIOLATION(422)`, `PASSWORD_REUSED(422)`, `PASSWORD_COMPROMISED(422)`, `VALIDATION_ERROR(400)`이다.

## 블로그 게시글

### 데이터 기준

- 기준 테이블은 `public.blog_post`이며 복수형 `blog_posts` 테이블은 사용하지 않는다.
- 한 `post_id`에 DRAFT는 최대 1개, PUBLISHED는 최대 1개다. PUBLISHED·ARCHIVED는 immutable이다.
- 현재 migration의 `public.blog_post`에는 `title`, `summary`, `category`, `media_asset_id`, `alt_text`만 있고 본문 컬럼이 없다.
- WYSIWYG 본문을 계약에 포함하므로 구현 전에 `content text null` 컬럼과 길이·HTML sanitization 정책을 추가 migration으로 확정한다. 구현자는 임의로 `summary`에 본문을 저장하지 않는다.
- DRAFT는 `content=null` 또는 빈 문자열을 허용하지만 PUBLISHED는 정제 후 비어 있지 않은 본문을 요구한다.
- 저장 허용 HTML 요소는 `p`, `br`, `strong`, `em`, `u`, `s`, `ul`, `ol`, `li`, `blockquote`, `h2`, `h3`, `a`, `img`다. 허용 속성은 `a[href]`, `a[target]`, `a[rel]`, `img[src]`, `img[alt]`, `img[width]`, `img[height]`뿐이다.
- `href`와 `src`는 `https://` 또는 내부 `/media/` 경로만 허용한다. `javascript:`, `data:`, `vbscript:`, inline event 속성, style 속성, iframe·script·form은 제거한다. 외부 이미지 `src`는 `https://`만 허용한다.
- 정제 후 본문은 최대 100,000 Unicode 문자이며, 초과하면 `BLOG_CONTENT_TOO_LARGE(422)`다. 허용되지 않은 HTML이나 URL은 자동 제거하지 않고 `BLOG_CONTENT_INVALID(422)`로 거부한다.
- 저장 시 서버에서 HTML을 canonical sanitize하고, 공개 응답 시에도 동일 sanitizer를 한 번 더 적용한다. 미리보기는 저장될 canonical 결과를 사용한다.

### 관리자 목록

`GET /api/admin/blog-posts`

권한: `BLOG_READ` · 세션 필요

Query: `keyword`(2~50자), `categories[]`(`CLASS_STORY|STUDIO_NEWS|ARTWORK_STORY`), `states[]`(`DRAFT_ONLY|PUBLISHED_VISIBLE|PUBLISHED_HIDDEN|HAS_DRAFT`), `sort`(`UPDATED_DESC|PUBLISHED_DESC|TITLE_ASC`), `page`(0 이상), `size`(`10|20|50`).

응답 `data`:

```json
{
  "page":0,"size":20,"totalElements":1,"totalPages":1,"sort":"UPDATED_DESC",
  "items":[{
    "postId":"uuid","displayTitle":"여름 수업 이야기","displayCategory":"CLASS_STORY",
    "displayThumbnail":{"mediaAssetId":"uuid","url":"/media/x.webp","altText":"작품","source":"DRAFT"},
    "author":{"adminUserId":"uuid","displayName":"원장","active":true},
    "published":{"revisionId":"uuid","revision":3,"visible":true,"publishedAt":"2026-10-03T10:00:00Z"},
    "draft":{"revisionId":"uuid","revision":4,"updatedAt":"2026-10-03T11:00:00Z","version":7,"publishable":true},
    "actions":{"canEdit":true,"canCreateDraft":true,"canPublish":true},
    "updatedAt":"2026-10-03T11:00:00Z"
  }]
}
```

### 초안 생성·상세

- `POST /api/admin/blog-posts`: `BLOG_WRITE`, `Idempotency-Key`, `201`; body의 `title`, `summary`, `category`, `content`, `mediaAssetId`, `altText`, `visible`은 생략 가능하며 DRAFT 기본값을 사용한다. 응답은 `postId`, `draftId`, `revision`, `version`, `status`다.
- `GET /api/admin/blog-posts/{postId}?mode=DRAFT|PUBLISHED`: `BLOG_READ`, `200`; 상세 data는 `postId`, `revisionId`, `draftId`, `revision`, `status`, `sourceStatus`, `editable`, `version`, `title`, `summary`, `category`, `content`, `mediaAsset`, `altText`, `visible`, `author`, `publishable`, `actions`, `updatedAt`을 포함한다.
- DRAFT가 없을 때 `mode=DRAFT`는 PUBLISHED 복사 모델(`editable=false`, `canCreateDraft=true`)을 반환한다. PUBLISHED가 없으면 `404 BLOG_POST_REVISION_NOT_FOUND`다.

### 초안 편집·미리보기·발행

- `POST /api/admin/blog-posts/{postId}/drafts`: `BLOG_WRITE`, `Idempotency-Key`, `201`; PUBLISHED를 기준으로 초안을 만든다. 기존 DRAFT는 `409 BLOG_DRAFT_EXISTS`다.
- `PUT /api/admin/blog-posts/{postId}/drafts/{draftId}`: `BLOG_WRITE`, `Idempotency-Key`; body는 `version`, `title`, `summary`, `category`, `content`, `mediaAssetId`, `altText`, `visible`이며 version 불일치는 `409 BLOG_DRAFT_VERSION_CONFLICT`다.
- `GET /api/admin/blog-posts/{postId}/drafts/{draftId}/preview?version={version}`: `BLOG_READ`; 비영속 미리보기 data는 상세 data와 동일한 `content`를 포함한다.
- `POST /api/admin/blog-posts/{postId}/publications`: `BLOG_PUBLISH`, `Idempotency-Key`; body `{ "draftId":"uuid", "version":7 }`; 공개 가능성·content sanitization·READY media를 검증하고 revision·audit를 원자 처리한다.

발행 실패 코드는 `BLOG_DRAFT_VERSION_CONFLICT(409)`, `BLOG_NOT_PUBLISHABLE(422)`, `BLOG_MEDIA_NOT_READY(422)`, `BLOG_POST_REVISION_NOT_FOUND(404)`다.

### 공개 목록

`GET /api/public/blog-posts`

인증 없음. `SecurityConfiguration`의 `anyRequest().denyAll()`을 유지하므로 이 경로를 `permitAll` allowlist에 명시한다. Query `category`, `keyword`, `page`, `size`; `status=PUBLISHED AND visible=true`이며 `deleted_at` 대신 현재 테이블의 `status`와 publication immutable 규칙을 사용한다. 응답 item은 `postId`, `title`, `summary`, `content`, `category`, `media`, `publishedAt`만 공개한다. 웹사이트는 현재 same-origin Next.js 프록시를 통해 호출한다.

## 문의

### 데이터 기준

- 기준 테이블은 `public.inquiry`와 `public.inquiry_activity`다. `contacts` 테이블은 사용하지 않는다.
- 이름·전화번호·메시지·activity note는 ciphertext로 저장하고 hash·last4만 검색과 마스킹에 사용한다.
- 상태는 `RECEIVED`, `CONTACTING`, `COMPLETED`, `UNREACHABLE`; version은 `public.inquiry.version`이다.

### 공개 접수

`POST /api/public/inquiries`

인증 없음 · `Idempotency-Key` UUID 필수 · `202 Accepted`.

요청: `name`(trim 1~50), `phone`(E.164 8~15), `interestedCourseId`(활성 course UUID/null), `message`(plain text 1~2000), `privacyConsent=true`, `consentPolicyVersion`(1~30), `company`(honeypot 빈 값).

응답은 항상 `data: {"accepted":true}`만 노출한다. 동의 누락 `PRIVACY_CONSENT_REQUIRED(422)`, 정책 버전 오류 `CONSENT_POLICY_VERSION_INVALID(422)`, 입력 오류 `INQUIRY_INVALID(400)`, rate limit `INQUIRY_RATE_LIMITED(429)`, 같은 key·다른 body `IDEMPOTENCY_KEY_REUSED(409)`다. 암호화 저장과 idempotency 기록이 성공 기준이며 알림 실패는 접수를 롤백하지 않는다.

### 관리자 목록·옵션·상세

- `GET /api/admin/inquiries/options`: `INQUIRY_READ`, `200`; 활성 course 선택지
- `GET /api/admin/inquiries?keyword&courseIds&statuses&from&to&readState&page&size`: `INQUIRY_READ`, `200`; item은 `inquiryId`, `name`, `maskedPhone`, `interestedCourse`, `status`, `read`, `receivedAt`, `lastActivityAt`, `stale`이며 원문·ciphertext·hash를 포함하지 않는다.
- `GET /api/admin/inquiries/{inquiryId}`: `INQUIRY_READ`, `200`; 권한 있는 관리자에게만 원문 전화번호·동의·알림·allowedTransitions·activities를 반환한다. 없거나 파기된 경우 `404 INQUIRY_NOT_FOUND`다.

관리자 목록 응답 예시:

```json
{
  "success": true,
  "data": {
    "page": 0,
    "size": 20,
    "totalElements": 12,
    "totalPages": 1,
    "summary": {"unreadCount": 3, "staleCount": 2},
    "items": [{
      "inquiryId": "uuid",
      "name": "홍길동",
      "maskedPhone": "***-****-5678",
      "interestedCourse": {"courseId":"uuid","name":"초등 창작"},
      "status": "RECEIVED",
      "read": false,
      "receivedAt": "2026-10-03T10:00:00Z",
      "lastActivityAt": "2026-10-03T10:00:00Z",
      "stale": false
    }]
  },
  "error": null,
  "requestId": "req_uuid"
}
```

`stale=true`는 `status`가 `COMPLETED`·`UNREACHABLE`이 아니고 `receivedAt`이 현재 UTC 시각보다 72시간 이상 지난 경우다. `lastActivityAt`은 가장 최근 activity의 `createdAt`, activity가 없으면 `receivedAt`이다. `summary`와 `items`는 동일한 DB snapshot에서 계산한다.

관리자 상세 응답 예시:

```json
{
  "success": true,
  "data": {
    "inquiryId": "uuid",
    "version": 1,
    "name": "홍길동",
    "phone": "+821012345678",
    "displayPhone": "010-1234-5678",
    "interestedCourse": {"courseId":"uuid","name":"초등 창작","active":true},
    "message": "초등부 수업 가능 시간을 문의합니다.",
    "status": "CONTACTING",
    "read": true,
    "readAt": "2026-10-03T10:10:00Z",
    "readBy": {"adminUserId":"uuid","displayName":"원장"},
    "consent": {"policyVersion":"2026-10","consentedAt":"2026-10-03T10:00:00Z"},
    "notification": {"status":"SENT","attemptedAt":"2026-10-03T10:00:05Z"},
    "allowedTransitions": ["COMPLETED","UNREACHABLE"],
    "activities": [{
      "activityId":"uuid",
      "fromStatus":"RECEIVED",
      "toStatus":"CONTACTING",
      "note":"전화 연결을 시도함",
      "createdBy":{"adminUserId":"uuid","displayName":"원장"},
      "createdAt":"2026-10-03T10:10:00Z",
      "inquiryVersion":1
    }]
  },
  "error": null,
  "requestId": "req_uuid"
}
```

### 읽음·상태 처리

- `POST /api/admin/inquiries/{inquiryId}/read-receipts`: `INQUIRY_READ`, `Idempotency-Key`, body `{ "inquiryVersion": 0 }`; 최초 읽음만 저장하고 version 불일치는 `409 INQUIRY_VERSION_CONFLICT`다.
- `POST /api/admin/inquiries/{inquiryId}/activities`: `INQUIRY_WRITE`, `Idempotency-Key`, body `{ "toStatus":"CONTACTING", "note":"전화 연결을 시도함", "inquiryVersion":1 }`; note 5~1000자, 허용 전이·조건부 update·activity·audit를 원자 처리한다.

오류 코드는 `INQUIRY_INVALID`, `INQUIRY_QUERY_INVALID`, `INQUIRY_NOT_FOUND`, `INQUIRY_VERSION_CONFLICT`, `INQUIRY_TRANSITION_DENIED`, `INQUIRY_NOTE_REQUIRED`, `PRIVACY_CONSENT_REQUIRED`, `CONSENT_POLICY_VERSION_INVALID`, `INQUIRY_RATE_LIMITED`, `INQUIRY_SAVE_FAILED`다.

## 사이트 브랜드 설정

### 데이터 기준과 필드 검증

기준 테이블은 `public.site_brand_config`다. DRAFT·PUBLISHED·ARCHIVED revision 모델과 version을 사용한다.

| 필드 | 자료형·제약 |
|---|---|
| `brandName` | string, 1~100 |
| `shortName` | string, 1~30 |
| `logoAssetId`, `faviconAssetId` | READY `media_asset` UUID, 필수 |
| `shareAssetId` | READY `media_asset` UUID, 선택 |
| `logoAltText` | string, 1~300 |
| `primaryColor` | 허용 palette `#1F2937`, `#1D4ED8`, `#166534`, `#7C2D12`, `#6B21A8` |
| `accentColor` | 허용 palette `#F59E0B`, `#0D9488`, `#DB2777`, `#2563EB`, `#DC2626` |
| `fontPreset` | `SYSTEM_SANS`, `SERIF_CLASSIC`, `ROUNDED_SANS` |
| `canonicalHost` | 소문자 `https://host[:port]` |
| `defaultTitle` | string, 1~60 |
| `defaultDescription` | string, 1~160 |
| `instagramUrl`, `blogUrl` | null 또는 `https://` URL, 최대 500 |

DB trigger가 logo·favicon·share asset의 READY 상태를 확인하고 favicon은 정사각형이어야 한다.

### 엔드포인트

- `GET /api/admin/site-brand`: `SITE_BRAND_READ`; PUBLISHED·DRAFT와 `editable`, `draftId`, `sourceStatus` 반환
- `POST /api/admin/site-brand/draft`: `SITE_BRAND_WRITE`, `Idempotency-Key`; 공개본 복사 또는 빈 초안 생성, `201`; 중복은 `409 SITE_BRAND_DRAFT_EXISTS`
- `PUT /api/admin/site-brand/draft/{draftId}`: `SITE_BRAND_WRITE`, `Idempotency-Key`; 위 필드를 모두 키로 받으며 `version` 불일치는 `409 SITE_BRAND_VERSION_CONFLICT`
- `POST /api/admin/site-brand/preview`: `SITE_BRAND_READ`; body는 저장 DTO와 동일하고 `version`은 선택; 응답은 `renderModel`, `contrastChecks[]`, `warnings[]`, `publishable`이다. 비영속이다.
- `POST /api/admin/site-brand/draft/{draftId}/publish`: `SITE_BRAND_PUBLISH`, `Idempotency-Key`; body `{ "version": 3 }`; revision·media reference·audit를 원자 처리한다.

관리자 조회 응답 예시:

```json
{
  "success": true,
  "data": {
    "editable": true,
    "draftId": "uuid",
    "sourceStatus": "DRAFT",
    "published": {
      "id":"uuid","revision":2,"status":"PUBLISHED","version":4,
      "brandName":"라미아트 미술교습소","shortName":"라미아트",
      "logoAssetId":"uuid","logoAltText":"라미아트 로고","faviconAssetId":"uuid","shareAssetId":"uuid",
      "primaryColor":"#1F2937","accentColor":"#F59E0B","fontPreset":"SYSTEM_SANS",
      "canonicalHost":"https://ramiartstudio.com","defaultTitle":"라미아트 미술교습소",
      "defaultDescription":"아이들의 창작을 돕는 미술교습소입니다.","instagramUrl":null,"blogUrl":null,
      "media":{"logoUrl":"/media/logo.webp","faviconUrl":"/media/favicon.webp","shareUrl":"/media/share.webp"},
      "updatedAt":"2026-10-03T10:00:00Z","publishedAt":"2026-10-03T10:00:00Z"
    },
    "draft": null
  },
  "error": null,
  "requestId": "req_uuid"
}
```

미리보기 요청과 응답 예시:

```json
POST /api/admin/site-brand/preview
{
  "version": 4,
  "brandName":"라미아트 미술교습소","shortName":"라미아트",
  "logoAssetId":"uuid","logoAltText":"라미아트 로고","faviconAssetId":"uuid","shareAssetId":"uuid",
  "primaryColor":"#1F2937","accentColor":"#F59E0B","fontPreset":"SYSTEM_SANS",
  "canonicalHost":"https://ramiartstudio.com","defaultTitle":"라미아트 미술교습소",
  "defaultDescription":"아이들의 창작을 돕는 미술교습소입니다.","instagramUrl":null,"blogUrl":null
}
```

```json
{
  "success": true,
  "data": {
    "renderModel": {
      "brandName":"라미아트 미술교습소","shortName":"라미아트",
      "logoUrl":"/media/logo.webp","faviconUrl":"/media/favicon.webp","shareUrl":"/media/share.webp",
      "primaryColor":"#1F2937","accentColor":"#F59E0B","fontPreset":"SYSTEM_SANS",
      "defaultTitle":"라미아트 미술교습소","defaultDescription":"아이들의 창작을 돕는 미술교습소입니다.",
      "canonicalHost":"https://ramiartstudio.com"
    },
    "contrastChecks":[{"pair":"primary-on-background","ratio":12.4,"passed":true}],
    "warnings":[],
    "publishable":true
  },
  "error": null,
  "requestId": "req_uuid"
}
```

### 공개 브랜드 조회

`GET /api/public/site-brand`

공개 웹사이트가 동적으로 브랜드 설정을 읽을 수 있도록 제공한다. 인증과 Origin 검증은 없고, `status=PUBLISHED`인 최신 revision만 반환한다. `SecurityConfiguration`의 `anyRequest().denyAll()`을 유지하므로 이 GET 경로를 `permitAll` allowlist에 명시한다. 웹사이트는 현재 same-origin Next.js 프록시를 통해 호출한다.

공개 응답은 내부 ID·version·작성자·초안을 제외한다.

```json
{
  "success": true,
  "data": {
    "revision": 2,
    "brandName":"라미아트 미술교습소","shortName":"라미아트",
    "logoUrl":"/media/logo.webp","logoAltText":"라미아트 로고",
    "faviconUrl":"/media/favicon.webp","shareImageUrl":"/media/share.webp",
    "primaryColor":"#1F2937","accentColor":"#F59E0B","fontPreset":"SYSTEM_SANS",
    "canonicalHost":"https://ramiartstudio.com","defaultTitle":"라미아트 미술교습소",
    "defaultDescription":"아이들의 창작을 돕는 미술교습소입니다.","instagramUrl":null,"blogUrl":null,
    "publishedAt":"2026-10-03T10:00:00Z"
  },
  "error": null,
  "requestId": "req_uuid"
}
```

공개 설정이 없으면 `404 SITE_BRAND_NOT_FOUND`이며 응답은 `ETag`와 `Cache-Control: public, max-age=60, stale-while-revalidate=300`을 사용할 수 있다. 비공개·DRAFT 값은 공개하지 않는다.

오류 코드는 `SITE_BRAND_VALIDATION_FAILED(400)`, `SITE_BRAND_ACCESS_DENIED(403)`, `SITE_BRAND_NOT_FOUND(404)`, `SITE_BRAND_VERSION_CONFLICT(409)`, `SITE_BRAND_DRAFT_EXISTS(409)`, `SITE_BRAND_CONTRAST_FAILED(422)`, `SITE_BRAND_MEDIA_NOT_READY(422)`, `SITE_BRAND_PERSISTENCE_FAILED(500)`다.

## 구현 전 migration 기준

- `public.inquiry`, `public.inquiry_activity`, `public.site_brand_config`, `public.blog_post`를 기준으로 하며 `contacts`, `blog_posts`, `site_settings`는 생성하지 않는다.
- `public.blog_post`에 본문을 저장하려면 별도 Flyway/Supabase migration으로 `content` 컬럼·길이 제한·sanitization 정책을 먼저 추가한다.
- 본문 migration은 `content text null`과 정제된 canonical HTML 저장을 정의하고, `PUBLISHED` 전환 시 비어 있지 않은 본문·허용 태그·허용 URL을 재검증한다.
- API 구현 전 migration reset, seed idempotency, RLS·grant, version·unique partial index 검증을 완료한다.

## 추적 문서

- 웹 Blog 설계: [blog-post.md](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/blog-post.md)
- 웹 문의 설계: [inquiry.md](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/inquiry.md)
- 웹 사이트 브랜드 설계: [site-brand.md](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/site-brand.md)
- 데이터 구조: [DATA-SPEC.md](./DATA-SPEC.md)

이 문서는 구현 입력 계약이다. 실제 구현이 완료되기 전까지 기존 `API-SPEC.md`의 구현 상태를 완료로 변경하지 않는다.
