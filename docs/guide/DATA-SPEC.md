# 🗄 라미아트미술교습소 — Data Specification

> **버전** v1.0 | **작성일** 2026-03-08 | **DB** Supabase PostgreSQL 16

---

## 📐 DB 설계 원칙

- 모든 테이블은 UUID Primary Key 사용 (`gen_random_uuid()`)
- **Soft Delete**: 중요 데이터는 `deleted_at` 컬럼으로 논리 삭제
- **감사 컬럼**: `created_at`, `updated_at` (TIMESTAMPTZ, 자동 관리)
- Snake_case 컬럼명 사용 (PostgreSQL 컨벤션)
- 외래키는 `ON DELETE CASCADE` 또는 `SET NULL` 명시
- Supabase Row Level Security (RLS) 적용

---

## 🗂 ERD 엔티티 관계

핵심 엔티티 관계 구조 (주요 참조 관계)

- `admin_users` — 관리자 계정 (Supabase Auth 연동)
- `site_settings` — 1:1 싱글턴 사이트 설정
- `classes` — 수업 정보 (1:N → `class_tags`)
- `gallery_categories` — 갤러리 분류 (1:N → `gallery_works`)
- `gallery_works` — 학생 작품
- `blog_posts` — 블로그 게시글
- `contacts` — 문의 접수
- `page_sections` — 페이지별 콘텐츠 블록

---

## 📊 테이블 상세 명세

### admin_users

Supabase Auth의 `auth.users`를 참조하는 관리자 확장 테이블

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 관리자 ID |
| `auth_user_id` | UUID | UNIQUE, FK → auth.users(id) | Supabase Auth 연동 ID |
| `name` | VARCHAR(100) | NOT NULL | 관리자 이름 |
| `email` | VARCHAR(255) | UNIQUE, NOT NULL | 이메일 |
| `role` | VARCHAR(20) | NOT NULL, DEFAULT 'admin' | 권한 (admin / super_admin) |
| `last_login_at` | TIMESTAMPTZ | NULL | 마지막 로그인 시각 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 생성일 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 수정일 |

---

### site_settings

사이트 전역 설정 (레코드 1개만 존재하는 싱글턴 테이블)

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 설정 ID (항상 1개) |
| `phone` | VARCHAR(20) | NULL | 대표 전화번호 |
| `email` | VARCHAR(255) | NULL | 대표 이메일 |
| `address` | TEXT | NULL | 주소 |
| `address_detail` | VARCHAR(255) | NULL | 상세 주소 |
| `business_hours` | JSONB | NULL | 운영 시간 JSON |
| `instagram_url` | TEXT | NULL | 인스타그램 URL |
| `parking_info` | TEXT | NULL | 주차 안내 |
| `transit_info` | TEXT | NULL | 대중교통 안내 |
| `meta_title` | VARCHAR(255) | NULL | SEO 타이틀 |
| `meta_description` | TEXT | NULL | SEO 설명 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 생성일 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 수정일 |

**`business_hours` JSONB 예시**

```json
{
  "weekday": "14:00-18:00",
  "saturday": "휴무",
  "sunday": "휴무",
  "holiday": "휴무"
}
```

---

### page_sections

홈/소개 페이지의 편집 가능한 텍스트 블록을 저장하는 범용 테이블

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 섹션 ID |
| `page_key` | VARCHAR(50) | NOT NULL | 페이지 식별자 (home, about 등) |
| `section_key` | VARCHAR(100) | NOT NULL | 섹션 식별자 (hero, features 등) |
| `title` | VARCHAR(255) | NULL | 섹션 제목 |
| `subtitle` | TEXT | NULL | 부제목 |
| `content` | TEXT | NULL | 본문 콘텐츠 |
| `extra_data` | JSONB | NULL | 추가 데이터 (통계 수치 등) |
| `sort_order` | INTEGER | NOT NULL, DEFAULT 0 | 섹션 내 순서 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | 노출 여부 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 생성일 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 수정일 |

> **UNIQUE 제약**: `(page_key, section_key)`

---

### classes

유치부/초등부/중등부 수업 정보

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 수업 ID |
| `age_group` | VARCHAR(20) | NOT NULL | 연령 그룹 (kindergarten / elementary / middle) |
| `age_range` | VARCHAR(50) | NOT NULL | 연령 범위 표시 (예: 5-7세) |
| `title` | VARCHAR(100) | NOT NULL | 수업명 |
| `description` | TEXT | NULL | 수업 설명 |
| `curriculum` | TEXT | NULL | 커리큘럼 상세 |
| `thumbnail_url` | TEXT | NULL | 썸네일 이미지 URL |
| `sort_order` | INTEGER | NOT NULL, DEFAULT 0 | 표시 순서 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | 노출 여부 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 생성일 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 수정일 |
| `deleted_at` | TIMESTAMPTZ | NULL | 삭제일 (소프트 삭제) |

---

### class_tags

수업별 태그/특징 키워드 (1:N → classes)

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 태그 ID |
| `class_id` | UUID | NOT NULL, FK → classes(id) ON DELETE CASCADE | 수업 참조 |
| `tag` | VARCHAR(100) | NOT NULL | 태그 내용 (예: 소근육 발달) |
| `sort_order` | INTEGER | NOT NULL, DEFAULT 0 | 표시 순서 |

---

### gallery_categories

갤러리 작품 분류

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 카테고리 ID |
| `name` | VARCHAR(100) | NOT NULL | 카테고리명 (예: 수채화) |
| `slug` | VARCHAR(100) | UNIQUE, NOT NULL | URL 슬러그 |
| `sort_order` | INTEGER | NOT NULL, DEFAULT 0 | 정렬 순서 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | 노출 여부 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 생성일 |

---

### gallery_works

학생 작품 이미지 (핵심 갤러리 테이블)

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 작품 ID |
| `category_id` | UUID | NULL, FK → gallery_categories(id) SET NULL | 카테고리 |
| `title` | VARCHAR(255) | NOT NULL | 작품명 |
| `description` | TEXT | NULL | 작품 설명 |
| `image_url` | TEXT | NOT NULL | 이미지 URL (Supabase Storage) |
| `image_path` | TEXT | NOT NULL | Storage 내 경로 (삭제 시 활용) |
| `age_group` | VARCHAR(20) | NULL | 연령대 (kindergarten / elementary / middle) |
| `artist_name` | VARCHAR(100) | NULL | 작가 이름 (학생 이름 또는 익명) |
| `created_year` | SMALLINT | NULL | 제작 연도 |
| `sort_order` | INTEGER | NOT NULL, DEFAULT 0 | 전시 순서 |
| `is_featured` | BOOLEAN | NOT NULL, DEFAULT false | 홈 미리보기 노출 |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | 노출 여부 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 등록일 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 수정일 |
| `deleted_at` | TIMESTAMPTZ | NULL | 삭제일 (소프트 삭제) |

---

### blog_posts

블로그/소식 게시글

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 게시글 ID |
| `title` | VARCHAR(500) | NOT NULL | 제목 |
| `slug` | VARCHAR(500) | UNIQUE, NULL | URL 슬러그 (자동 생성) |
| `content` | TEXT | NULL | 본문 (HTML) |
| `excerpt` | TEXT | NULL | 요약 (목록 표시용) |
| `thumbnail_url` | TEXT | NULL | 썸네일 이미지 URL |
| `thumbnail_path` | TEXT | NULL | Storage 경로 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'draft' | 상태 (draft / published) |
| `published_at` | TIMESTAMPTZ | NULL | 발행일 |
| `author_id` | UUID | NULL, FK → admin_users(id) SET NULL | 작성자 |
| `view_count` | INTEGER | NOT NULL, DEFAULT 0 | 조회수 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 생성일 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 수정일 |
| `deleted_at` | TIMESTAMPTZ | NULL | 삭제일 (소프트 삭제) |

---

### contacts

홈페이지 문의 폼 접수 내역

| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| `id` | UUID | PK, DEFAULT gen_random_uuid() | 문의 ID |
| `name` | VARCHAR(100) | NOT NULL | 이름 |
| `phone` | VARCHAR(20) | NOT NULL | 연락처 |
| `interest_class` | VARCHAR(50) | NULL | 관심 수업 |
| `message` | TEXT | NOT NULL | 문의 내용 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'unread' | 상태 (unread / read / replied) |
| `admin_memo` | TEXT | NULL | 관리자 메모 |
| `ip_address` | VARCHAR(45) | NULL | 발신 IP (스팸 방지) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 접수일 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 수정일 |
| `deleted_at` | TIMESTAMPTZ | NULL | 삭제일 |

---

## ⚡ 인덱스 설계

| 테이블 | 인덱스 컬럼 | 인덱스명 | 목적 |
|--------|-------------|----------|------|
| `gallery_works` | `category_id` | `idx_gallery_works_category` | 카테고리 필터 |
| `gallery_works` | `is_featured, is_active, deleted_at` | `idx_gallery_works_featured` | 홈 미리보기 쿼리 |
| `gallery_works` | `sort_order` | `idx_gallery_works_sort` | 정렬 |
| `blog_posts` | `status, published_at` | `idx_blog_posts_status_date` | 발행 목록 쿼리 |
| `blog_posts` | `slug` | `idx_blog_posts_slug` | URL 조회 |
| `blog_posts` | `deleted_at` | `idx_blog_posts_deleted` | 소프트 삭제 필터 |
| `contacts` | `status, created_at` | `idx_contacts_status_date` | 문의 목록 쿼리 |
| `classes` | `age_group, is_active` | `idx_classes_age_active` | 연령별 필터 |
| `page_sections` | `page_key, section_key` | `idx_page_sections_keys` | 페이지 콘텐츠 조회 |

---

## 🔒 Row Level Security 정책

| 테이블 | 읽기 (SELECT) | 쓰기 (INSERT/UPDATE/DELETE) |
|--------|--------------|---------------------------|
| `site_settings` | 공개 (anon 허용) | 인증된 관리자만 |
| `page_sections` | `is_active = true` 공개 | 인증된 관리자만 |
| `classes` | `is_active = true`, `deleted_at IS NULL` 공개 | 인증된 관리자만 |
| `gallery_works` | `is_active = true`, `deleted_at IS NULL` 공개 | 인증된 관리자만 |
| `gallery_categories` | `is_active = true` 공개 | 인증된 관리자만 |
| `blog_posts` | `status = 'published'`, `deleted_at IS NULL` 공개 | 인증된 관리자만 |
| `contacts` | 비공개 (관리자만) | anon INSERT 허용 (문의 폼) |
| `admin_users` | 비공개 (관리자 자신만) | 관리자 자신만 |

---

## 🗃 Supabase Storage 구조

| 버킷명 | 접근 정책 | 저장 경로 패턴 | 용도 |
|--------|-----------|----------------|------|
| `gallery` | Public (읽기 공개) | `gallery/{year}/{uuid}.{ext}` | 갤러리 작품 이미지 |
| `blog` | Public (읽기 공개) | `blog/{year}/{uuid}.{ext}` | 블로그 썸네일/본문 이미지 |
| `classes` | Public (읽기 공개) | `classes/{uuid}.{ext}` | 수업 썸네일 |
| `temp` | Private | `temp/{session_id}/{filename}` | 업로드 임시 저장 |

- 허용 파일 형식: JPEG, PNG, WebP, GIF
- 최대 파일 크기: 10MB
- 업로드 시 백엔드에서 파일 검증 후 Storage 저장
- 삭제 시 DB 레코드와 Storage 파일 동시 처리 (트랜잭션 고려)

---

## 📝 DDL 핵심 스크립트

```sql
-- UUID extension 활성화
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- updated_at 자동 갱신 함수
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- gallery_categories
CREATE TABLE gallery_categories (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name        VARCHAR(100) NOT NULL,
  slug        VARCHAR(100) UNIQUE NOT NULL,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  is_active   BOOLEAN NOT NULL DEFAULT true,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- gallery_works
CREATE TABLE gallery_works (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  category_id  UUID REFERENCES gallery_categories(id) ON DELETE SET NULL,
  title        VARCHAR(255) NOT NULL,
  description  TEXT,
  image_url    TEXT NOT NULL,
  image_path   TEXT NOT NULL,
  age_group    VARCHAR(20),
  artist_name  VARCHAR(100),
  created_year SMALLINT,
  sort_order   INTEGER NOT NULL DEFAULT 0,
  is_featured  BOOLEAN NOT NULL DEFAULT false,
  is_active    BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at   TIMESTAMPTZ
);

CREATE TRIGGER trg_gallery_works_updated
  BEFORE UPDATE ON gallery_works
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- blog_posts
CREATE TABLE blog_posts (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  title          VARCHAR(500) NOT NULL,
  slug           VARCHAR(500) UNIQUE,
  content        TEXT,
  excerpt        TEXT,
  thumbnail_url  TEXT,
  thumbnail_path TEXT,
  status         VARCHAR(20) NOT NULL DEFAULT 'draft',
  published_at   TIMESTAMPTZ,
  author_id      UUID REFERENCES admin_users(id) ON DELETE SET NULL,
  view_count     INTEGER NOT NULL DEFAULT 0,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at     TIMESTAMPTZ
);

CREATE TRIGGER trg_blog_posts_updated
  BEFORE UPDATE ON blog_posts
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- contacts
CREATE TABLE contacts (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            VARCHAR(100) NOT NULL,
  phone           VARCHAR(20) NOT NULL,
  interest_class  VARCHAR(50),
  message         TEXT NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'unread',
  admin_memo      TEXT,
  ip_address      VARCHAR(45),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at      TIMESTAMPTZ
);

-- 인덱스
CREATE INDEX idx_gallery_works_category  ON gallery_works (category_id);
CREATE INDEX idx_gallery_works_featured  ON gallery_works (is_featured, is_active, deleted_at);
CREATE INDEX idx_gallery_works_sort      ON gallery_works (sort_order);
CREATE INDEX idx_blog_posts_status_date  ON blog_posts (status, published_at);
CREATE INDEX idx_blog_posts_slug         ON blog_posts (slug);
CREATE INDEX idx_blog_posts_deleted      ON blog_posts (deleted_at);
CREATE INDEX idx_contacts_status_date    ON contacts (status, created_at);
```

---

> © 2026 라미아트미술교습소 Data Spec v1.0