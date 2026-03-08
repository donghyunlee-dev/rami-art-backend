CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE SCHEMA IF NOT EXISTS rami_art_studio;

CREATE OR REPLACE FUNCTION rami_art_studio.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE IF NOT EXISTS rami_art_studio.admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id UUID UNIQUE,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'admin' CHECK (role IN ('admin', 'super_admin')),
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rami_art_studio.site_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone VARCHAR(20),
    email VARCHAR(255),
    address TEXT,
    address_detail VARCHAR(255),
    business_hours JSONB,
    instagram_url TEXT,
    parking_info TEXT,
    transit_info TEXT,
    meta_title VARCHAR(255),
    meta_description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rami_art_studio.page_sections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_key VARCHAR(50) NOT NULL,
    section_key VARCHAR(100) NOT NULL,
    title VARCHAR(255),
    subtitle TEXT,
    content TEXT,
    extra_data JSONB,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_page_sections_page_section UNIQUE (page_key, section_key)
);

CREATE TABLE IF NOT EXISTS rami_art_studio.classes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    age_group VARCHAR(20) NOT NULL CHECK (age_group IN ('kindergarten', 'elementary', 'middle')),
    age_range VARCHAR(50) NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT,
    curriculum TEXT,
    thumbnail_url TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS rami_art_studio.class_tags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id UUID NOT NULL REFERENCES rami_art_studio.classes(id) ON DELETE CASCADE,
    tag VARCHAR(100) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS rami_art_studio.gallery_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rami_art_studio.gallery_works (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID REFERENCES rami_art_studio.gallery_categories(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    image_url TEXT NOT NULL,
    image_path TEXT NOT NULL,
    age_group VARCHAR(20) CHECK (age_group IN ('kindergarten', 'elementary', 'middle')),
    artist_name VARCHAR(100),
    created_year SMALLINT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_featured BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS rami_art_studio.blog_posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(500) NOT NULL,
    slug VARCHAR(500) UNIQUE,
    content TEXT,
    excerpt TEXT,
    thumbnail_url TEXT,
    thumbnail_path TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published')),
    published_at TIMESTAMPTZ,
    author_id UUID REFERENCES rami_art_studio.admin_users(id) ON DELETE SET NULL,
    view_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS rami_art_studio.contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    interest_class VARCHAR(50),
    message TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'unread' CHECK (status IN ('unread', 'read', 'replied')),
    admin_memo TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

DROP TRIGGER IF EXISTS trg_admin_users_updated_at ON rami_art_studio.admin_users;
CREATE TRIGGER trg_admin_users_updated_at
BEFORE UPDATE ON rami_art_studio.admin_users
FOR EACH ROW EXECUTE FUNCTION rami_art_studio.set_updated_at();

DROP TRIGGER IF EXISTS trg_site_settings_updated_at ON rami_art_studio.site_settings;
CREATE TRIGGER trg_site_settings_updated_at
BEFORE UPDATE ON rami_art_studio.site_settings
FOR EACH ROW EXECUTE FUNCTION rami_art_studio.set_updated_at();

DROP TRIGGER IF EXISTS trg_page_sections_updated_at ON rami_art_studio.page_sections;
CREATE TRIGGER trg_page_sections_updated_at
BEFORE UPDATE ON rami_art_studio.page_sections
FOR EACH ROW EXECUTE FUNCTION rami_art_studio.set_updated_at();

DROP TRIGGER IF EXISTS trg_classes_updated_at ON rami_art_studio.classes;
CREATE TRIGGER trg_classes_updated_at
BEFORE UPDATE ON rami_art_studio.classes
FOR EACH ROW EXECUTE FUNCTION rami_art_studio.set_updated_at();

DROP TRIGGER IF EXISTS trg_gallery_works_updated_at ON rami_art_studio.gallery_works;
CREATE TRIGGER trg_gallery_works_updated_at
BEFORE UPDATE ON rami_art_studio.gallery_works
FOR EACH ROW EXECUTE FUNCTION rami_art_studio.set_updated_at();

DROP TRIGGER IF EXISTS trg_blog_posts_updated_at ON rami_art_studio.blog_posts;
CREATE TRIGGER trg_blog_posts_updated_at
BEFORE UPDATE ON rami_art_studio.blog_posts
FOR EACH ROW EXECUTE FUNCTION rami_art_studio.set_updated_at();

DROP TRIGGER IF EXISTS trg_contacts_updated_at ON rami_art_studio.contacts;
CREATE TRIGGER trg_contacts_updated_at
BEFORE UPDATE ON rami_art_studio.contacts
FOR EACH ROW EXECUTE FUNCTION rami_art_studio.set_updated_at();
