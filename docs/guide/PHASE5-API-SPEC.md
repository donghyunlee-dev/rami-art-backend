# 🧩 콘텐츠·문의·사이트 설정 API 명세

> 상태: 개발용 계약 확정 전 검토본
>
> 구현 상태: 미구현
>
> 기준: 웹 관리자 설계 문서와 백엔드 공통 API envelope

## 공통 계약

- Base URL은 `/api`이며 모든 경로는 `/api/v1` 버전을 사용한다.
- 성공 응답은 `{"success":true,"data":{},"message":"처리 완료","error":null}`이다.
- 실패 응답은 `{"success":false,"data":null,"message":null,"error":{"code":"...","message":"...","fieldErrors":[]},"requestId":"..."}`이다.
- 모든 응답은 `X-Request-Id`를 반환한다. 관리자 응답은 `Cache-Control: private, no-store`를 사용한다.
- 관리자 API는 `__Host-rami_admin_session` 세션 쿠키와 허용 Origin 검증을 모두 통과해야 한다.
- 상태를 변경하는 요청은 `Idempotency-Key`를 필수로 받는다. 같은 key·같은 body는 기존 결과를 반환하고, 같은 key·다른 body는 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
- ID는 UUID 문자열, 일시는 ISO-8601 offset datetime, 금액은 KRW 정수로 표현한다.
- 모든 변경은 권한 검사 → 입력 검증 → version/idempotency 확인 → 데이터 저장 → 감사 로그 기록의 순서로 한 트랜잭션에서 처리한다.

## 블로그 게시글

### 관리자 목록

`GET /api/v1/admin/blog-posts`

권한: `BLOG_READ`

Query:

| 이름 | 타입 | 기본값 | 규칙 |
|---|---|---|---|
| `keyword` | string | 없음 | trim 후 2~50자, 제목 부분 검색 |
| `categories` | string[] | 전체 | `CLASS_STORY`, `STUDIO_NEWS`, `ARTWORK_STORY` |
| `states` | string[] | 전체 | `DRAFT_ONLY`, `PUBLISHED_VISIBLE`, `PUBLISHED_HIDDEN`, `HAS_DRAFT` |
| `sort` | string | `UPDATED_DESC` | `UPDATED_DESC`, `PUBLISHED_DESC`, `TITLE_ASC` |
| `page` | integer | `0` | 0 이상 |
| `size` | integer | `20` | `10`, `20`, `50` |

`data`는 `page`, `size`, `totalElements`, `totalPages`, `sort`, `items[]`를 반환한다. 목록 item은 `postId`, 표시용 제목·카테고리·썸네일, 작성자, `published`, `draft`, `actions`, `updatedAt`을 포함한다. DRAFT 또는 PUBLISHED가 없으면 해당 객체는 `null`이다.

### 신규 초안

`POST /api/v1/admin/blog-posts`

권한: `BLOG_WRITE` · 헤더: `Idempotency-Key`

요청 body:

```json
{
  "title": "",
  "summary": "",
  "category": null,
  "mediaAssetId": null,
  "altText": "",
  "visible": false
}
```

모든 필드는 생략 가능하며 기본값을 사용한다. 응답은 `201 Created`와 `postId`, `draftId`, `revision`, `version`, `status=DRAFT`를 반환한다.

### 상세 조회

`GET /api/v1/admin/blog-posts/{postId}?mode=PUBLISHED|DRAFT`

권한: `BLOG_READ`

`mode=DRAFT`에서 초안이 없으면 최신 발행본을 복사 가능한 읽기 모델로 반환한다. 이때 `editable=false`, `draftId=null`, `actions.canCreateDraft=true`, `actions.canSave=false`, `actions.canPublish=false`를 사용한다. 발행본이 없으면 `404 BLOG_POST_REVISION_NOT_FOUND`다.

### 발행본에서 초안 생성

`POST /api/v1/admin/blog-posts/{postId}/drafts`

권한: `BLOG_WRITE` · 헤더: `Idempotency-Key`

기존 발행본을 기준으로 초안을 만들고 `201 Created`를 반환한다. 이미 초안이 있으면 `409 BLOG_DRAFT_EXISTS`다.

### 초안 저장

`PUT /api/v1/admin/blog-posts/{postId}/drafts/{draftId}`

권한: `BLOG_WRITE` · 헤더: `Idempotency-Key`

요청은 `version`, `title`, `summary`, `category`, `mediaAssetId`, `altText`, `visible`을 받는다. version이 현재 값과 다르면 `409 BLOG_DRAFT_VERSION_CONFLICT`다. DRAFT는 불완전한 값을 허용하고, 발행 시에만 공개 가능성 검증을 수행한다.

### 미리보기

`GET /api/v1/admin/blog-posts/{postId}/drafts/{draftId}/preview?version={version}`

권한: `BLOG_READ`

저장하지 않는 미리보기 모델을 반환한다. version이 다르면 `409 BLOG_DRAFT_VERSION_CONFLICT`다.

### 발행

`POST /api/v1/admin/blog-posts/{postId}/publications`

권한: `BLOG_PUBLISH` · 헤더: `Idempotency-Key`

요청 body는 `draftId`, `version`을 받는다. 제목·요약·카테고리·본문·대표 미디어·alt text·공개 상태 검증, revision 생성, media reference, 감사 로그를 원자적으로 처리한다. 검증 실패는 `422 BLOG_NOT_PUBLISHABLE`, version 충돌은 `409 BLOG_DRAFT_VERSION_CONFLICT`다.

### 공개 목록

`GET /api/v1/public/blog-posts`

인증 없이 호출할 수 있다. `category`, `keyword`, `page`, `size`를 지원하며 `status=PUBLISHED`이고 `deleted_at IS NULL`인 게시글만 반환한다. 관리자 초안과 비공개 발행본은 절대 노출하지 않는다.

## 문의

### 공개 문의 접수

`POST /api/v1/public/inquiries`

헤더: `Idempotency-Key` UUID 필수

요청 body:

| 필드 | 타입 | 규칙 |
|---|---|---|
| `name` | string | trim 1~50자 |
| `phone` | string | E.164 정규화 8~15자리 |
| `interestedCourseId` | UUID/null | 활성 course 또는 null |
| `message` | string | plain text trim 1~2000자 |
| `privacyConsent` | boolean | 반드시 true |
| `consentPolicyVersion` | string | 현재 공개 form version |
| `company` | string | honeypot, 정상 요청은 빈 값 |

정상 요청과 honeypot 모두 `202 Accepted`, `data.accepted=true`만 외부에 반환한다. 같은 idempotency key와 같은 body는 같은 결과를 반환하고, 다른 body는 `409 IDEMPOTENCY_KEY_REUSED`다. 문의 저장·암호화·idempotency 기록은 원자 처리하며 알림 실패는 접수를 롤백하지 않는다.

### 관리자 목록

`GET /api/v1/admin/inquiries?keyword&courseIds&statuses&from&to&readState&page&size`

권한: `INQUIRY_READ`

검색 규칙은 이름 또는 전체 전화번호 정확 검색이며, `statuses`는 `RECEIVED`, `CONTACTING`, `COMPLETED`, `UNREACHABLE`을 지원한다. `from <= to`, 조회 기간 최대 3년, page는 0 기반, size는 `10`, `20`, `50`이다. 응답 item에는 원문 전화번호가 아닌 `maskedPhone`만 포함한다. `summary.unreadCount`, `summary.staleCount`는 목록과 같은 snapshot에서 계산한다.

### 상세 조회

`GET /api/v1/admin/inquiries/{inquiryId}`

권한: `INQUIRY_READ`

권한 있는 관리자에게만 전화번호 원문, 동의, 알림 상태, 허용 상태 전이, activity 이력을 반환한다. GET은 읽음 상태를 변경하지 않는다. 존재하지 않거나 파기된 문의는 `404 INQUIRY_NOT_FOUND`다.

### 읽음 처리

`POST /api/v1/admin/inquiries/{inquiryId}/read-receipts`

권한: `INQUIRY_WRITE` · 헤더: `Idempotency-Key`

요청 body는 `{ "inquiryVersion": 0 }`이다. 최초 읽음만 `readAt`, `readBy`, version을 저장한다. 미읽음 상태에서 version이 다르면 `409 INQUIRY_VERSION_CONFLICT`다. 이미 읽음이면 기존 receipt를 멱등 반환하며 readBy/readAt을 덮어쓰지 않는다.

### 상태·메모 기록

`POST /api/v1/admin/inquiries/{inquiryId}/activities`

권한: `INQUIRY_WRITE` · 헤더: `Idempotency-Key`

요청 body:

```json
{
  "toStatus": "CONTACTING",
  "note": "전화 연결을 시도함",
  "inquiryVersion": 1
}
```

허용 전이와 version을 검증하고 inquiry 조건부 update, 암호화 activity, 감사 로그, idempotency 결과를 원자 처리한다. note는 5~1000자다.

문의 오류 코드는 `INQUIRY_INVALID`, `INQUIRY_QUERY_INVALID`, `INQUIRY_NOT_FOUND`, `INQUIRY_VERSION_CONFLICT`, `INQUIRY_TRANSITION_DENIED`, `INQUIRY_NOTE_REQUIRED`, `PRIVACY_CONSENT_REQUIRED`, `CONSENT_POLICY_VERSION_INVALID`, `INQUIRY_RATE_LIMITED`, `INQUIRY_SAVE_FAILED`를 사용한다.

## 사이트 브랜드 설정

### 관리자 조회

`GET /api/v1/admin/site-brand`

권한: `SITE_BRAND_READ`

PUBLISHED와 DRAFT를 함께 반환한다. 응답은 `editable`, `draftId`, `sourceStatus`, `published`, `draft`를 포함한다.

### 초안 생성

`POST /api/v1/admin/site-brand/draft`

권한: `SITE_BRAND_WRITE` · 헤더: `Idempotency-Key`

공개본을 복사하거나 빈 초안을 생성한다. 중복 초안은 `409 SITE_BRAND_DRAFT_EXISTS`다.

### 초안 저장

`PUT /api/v1/admin/site-brand/draft/{draftId}`

권한: `SITE_BRAND_WRITE` · 헤더: `Idempotency-Key`

요청은 다음 필드를 키 누락 없이 받는다: `version`, `brandName`, `shortName`, `logoAssetId`, `logoAltText`, `faviconAssetId`, `shareAssetId`, `primaryColor`, `accentColor`, `fontPreset`, `canonicalHost`, `defaultTitle`, `defaultDescription`, `instagramUrl`, `blogUrl`. optional 값은 `null`, 빈 문자열은 허용하지 않는다.

### 미리보기

`POST /api/v1/admin/site-brand/preview`

권한: `SITE_BRAND_READ`

비영속 `renderModel`, `contrastChecks[{pair,ratio,passed}]`, `warnings[]`, `publishable`을 반환한다.

### 발행

`POST /api/v1/admin/site-brand/draft/{draftId}/publish`

권한: `SITE_BRAND_PUBLISH` · 헤더: `Idempotency-Key`

revision·media reference·감사 로그를 원자 처리한다. version 충돌은 `409 SITE_BRAND_VERSION_CONFLICT`, 대비 실패는 `422 SITE_BRAND_CONTRAST_FAILED`, 미디어 준비 실패는 `422 SITE_BRAND_MEDIA_NOT_READY`다.

## 데이터·보안 매핑

- Blog: `blog_posts`, `media_asset`, `media_asset_reference`, `audit_log`, `idempotency_record`
- 문의: `contacts`, `inquiry_activity`, `course`, `audit_log`, `idempotency_record`
- 사이트 브랜드: `site_brand_config`, `media_asset`, `media_asset_reference`, `audit_log`, `idempotency_record`
- 관리자 쓰기 API는 session, Origin, 권한, CSRF 정책을 모두 적용한다.
- 공개 문의의 전화번호·메시지는 암호화 저장하며 목록에는 마스킹 값만 반환한다.
- 공개 Blog는 발행 상태와 삭제 여부를 서버에서 강제한다.

## 추적 링크

- 웹 Blog 설계: [blog-post.md](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/blog-post.md)
- 웹 문의 설계: [inquiry.md](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/inquiry.md)
- 웹 사이트 브랜드 설계: [site-brand.md](https://github.com/donghyunlee-dev/v0-rami-art-studio-web/blob/master/docs/management/API/site-brand.md)
- 백엔드 데이터 기준: [DATA-SPEC.md](./DATA-SPEC.md)

이 문서는 Phase 5 구현의 입력 계약이다. 구현이 완료되기 전까지 `API-SPEC.md`의 구현 상태를 완료로 변경하지 않는다.
