INSERT INTO rami_art_studio.gallery_categories (
    id,
    name,
    slug,
    sort_order,
    is_active,
    created_at
)
VALUES
(
    '51111111-1111-1111-1111-111111111111',
    '수채화',
    'watercolor',
    1,
    true,
    now()
),
(
    '52222222-2222-2222-2222-222222222222',
    '드로잉',
    'drawing',
    2,
    true,
    now()
)
ON CONFLICT (slug)
DO UPDATE SET
    name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order,
    is_active = EXCLUDED.is_active;

UPDATE rami_art_studio.gallery_works
SET category_id = '51111111-1111-1111-1111-111111111111',
    title = '봄 풍경 수채화',
    description = '초등부 학생이 완성한 봄 풍경 수채화 작품입니다.',
    image_url = 'https://example.com/gallery/watercolor-spring.jpg',
    image_path = 'gallery/2026/watercolor-spring.jpg',
    age_group = 'elementary',
    artist_name = '김하늘',
    created_year = 2026,
    sort_order = 1,
    is_featured = true,
    is_active = true,
    deleted_at = NULL
WHERE id = '53111111-1111-1111-1111-111111111111';

INSERT INTO rami_art_studio.gallery_works (
    id,
    category_id,
    title,
    description,
    image_url,
    image_path,
    age_group,
    artist_name,
    created_year,
    sort_order,
    is_featured,
    is_active
)
SELECT
    '53111111-1111-1111-1111-111111111111',
    '51111111-1111-1111-1111-111111111111',
    '봄 풍경 수채화',
    '초등부 학생이 완성한 봄 풍경 수채화 작품입니다.',
    'https://example.com/gallery/watercolor-spring.jpg',
    'gallery/2026/watercolor-spring.jpg',
    'elementary',
    '김하늘',
    2026,
    1,
    true,
    true
WHERE NOT EXISTS (
    SELECT 1 FROM rami_art_studio.gallery_works WHERE id = '53111111-1111-1111-1111-111111111111'
);

UPDATE rami_art_studio.gallery_works
SET category_id = '52222222-2222-2222-2222-222222222222',
    title = '기초 드로잉 정물화',
    description = '연필 명암 표현을 연습한 정물 드로잉 작품입니다.',
    image_url = 'https://example.com/gallery/drawing-still-life.jpg',
    image_path = 'gallery/2026/drawing-still-life.jpg',
    age_group = 'middle',
    artist_name = '이서준',
    created_year = 2025,
    sort_order = 2,
    is_featured = false,
    is_active = true,
    deleted_at = NULL
WHERE id = '53222222-2222-2222-2222-222222222222';

INSERT INTO rami_art_studio.gallery_works (
    id,
    category_id,
    title,
    description,
    image_url,
    image_path,
    age_group,
    artist_name,
    created_year,
    sort_order,
    is_featured,
    is_active
)
SELECT
    '53222222-2222-2222-2222-222222222222',
    '52222222-2222-2222-2222-222222222222',
    '기초 드로잉 정물화',
    '연필 명암 표현을 연습한 정물 드로잉 작품입니다.',
    'https://example.com/gallery/drawing-still-life.jpg',
    'gallery/2026/drawing-still-life.jpg',
    'middle',
    '이서준',
    2025,
    2,
    false,
    true
WHERE NOT EXISTS (
    SELECT 1 FROM rami_art_studio.gallery_works WHERE id = '53222222-2222-2222-2222-222222222222'
);
