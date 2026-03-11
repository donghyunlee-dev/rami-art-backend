DO $$
BEGIN
    UPDATE rami_art_studio.site_settings
    SET phone = '010-6858-3758',
        email = 'school579@naver.com',
        address = '인천광역시 남동구 호구포로 920',
        address_detail = '1층 107호',
        business_hours = jsonb_build_object(
            'weekday', '14:00-18:00',
            'saturday', '휴무',
            'sunday', '휴무',
            'holiday', '휴무'
        ),
        parking_info = '상가 뒤 주차 가능 (1시간 무료)',
        meta_title = '라미아트 미술교습소 - 유치부, 초등부, 중등부 미술 교육',
        meta_description = '인천 만수동에 위치한 라미아트 미술교습소입니다. 유치부, 초등부, 중등부 대상 소수정예 미술 교육을 제공합니다.'
    WHERE true;

    IF NOT FOUND THEN
        INSERT INTO rami_art_studio.site_settings (
            id,
            phone,
            email,
            address,
            address_detail,
            business_hours,
            parking_info,
            meta_title,
            meta_description
        )
        VALUES (
            '11111111-1111-1111-1111-111111111111',
            '010-6858-3758',
            'school579@naver.com',
            '인천광역시 남동구 호구포로 920',
            '1층 107호',
            jsonb_build_object(
                'weekday', '14:00-18:00',
                'saturday', '휴무',
                'sunday', '휴무',
                'holiday', '휴무'
            ),
            '상가 뒤 주차 가능 (1시간 무료)',
            '라미아트 미술교습소 - 유치부, 초등부, 중등부 미술 교육',
            '인천 만수동에 위치한 라미아트 미술교습소입니다. 유치부, 초등부, 중등부 대상 소수정예 미술 교육을 제공합니다.'
        );
    END IF;
END $$;

INSERT INTO rami_art_studio.page_sections (
    id,
    page_key,
    section_key,
    title,
    subtitle,
    content,
    extra_data,
    sort_order,
    is_active
)
VALUES
(
    '21111111-1111-1111-1111-111111111111',
    'home',
    'hero',
    '창의력이 피어나는 곳',
    '소수정예 맞춤 미술 교육',
    '최대 5명의 소규모 수업으로 아이 한 명 한 명의 잠재력을 발견하고 키워갑니다.',
    jsonb_build_object(
        'primaryCtaLabel', '수업 알아보기',
        'primaryCtaPath', '/classes',
        'secondaryCtaLabel', '갤러리 보기',
        'secondaryCtaPath', '/gallery',
        'stats', jsonb_build_array(
            jsonb_build_object('label', '최대 수업 인원', 'value', '5명'),
            jsonb_build_object('label', '연령별 과정', 'value', '3개'),
            jsonb_build_object('label', '년 교육 경험', 'value', '7+')
        )
    ),
    1,
    true
),
(
    '22222222-2222-2222-2222-222222222222',
    'home',
    'features',
    '라미아트 미술교습소의 특별함',
    'Why Lami Art Studio',
    '단순한 미술 기술 교육이 아닌, 아이들의 창의성과 자기표현 능력을 키우는 체계적인 교육 프로그램을 제공합니다.',
    jsonb_build_object(
        'items', jsonb_build_array(
            jsonb_build_object(
                'title', '다양한 미술 경험',
                'description', '드로잉, 페인팅, 입체 조형, 공예 등 다양한 미술 활동을 통해 폭넓은 예술 경험을 제공합니다.'
            ),
            jsonb_build_object(
                'title', '소수정예 맞춤 교육',
                'description', '최대 5명의 소규모 수업으로 아이 한 명 한 명에게 집중하는 맞춤형 교육을 제공합니다.'
            ),
            jsonb_build_object(
                'title', '창의력 중심 커리큘럼',
                'description', '정해진 답이 아닌, 아이들의 자유로운 상상력과 표현을 존중하는 교육을 지향합니다.'
            )
        )
    ),
    2,
    true
),
(
    '23333333-3333-3333-3333-333333333333',
    'about',
    'intro',
    '라미아트 소개',
    'About Us',
    '라미아트 미술교습소는 아이 한 명, 한 명의 시선과 표현을 존중하는 미술 수업을 합니다.\n\n다양한 재료와 주제 경험을 통해 스스로 느끼고 생각하며 표현하는 힘을 키워갑니다.\n\n미술이 즐거운 경험이자 성장의 시간이 되도록 함께합니다.',
    jsonb_build_object(
        'ctaLabel', '수업 알아보기',
        'ctaPath', '/classes'
    ),
    1,
    true
),
(
    '24444444-4444-4444-4444-444444444444',
    'about',
    'philosophy',
    '교육 철학',
    'Our Philosophy',
    '라미아트 미술교습소는 모든 아이들이 가진 고유한 창의성을 존중하고, 그것을 표현할 수 있는 기술과 자신감을 키워주는 것을 목표로 합니다.',
    jsonb_build_object(
        'items', jsonb_build_array(
            jsonb_build_object('title', '창의적 사고력 향상', 'description', '정해진 답이 아닌, 다양한 관점에서 문제를 바라보고 해결하는 창의적 사고력을 키웁니다.'),
            jsonb_build_object('title', '자기표현 능력 개발', 'description', '자신의 생각과 감정을 시각적으로 표현하는 과정을 통해 자기표현 능력을 향상시킵니다.'),
            jsonb_build_object('title', '예술적 감성 함양', 'description', '다양한 미술 작품과 기법을 경험하며 예술적 감성과 심미안을 기릅니다.')
        )
    ),
    2,
    true
),
(
    '25555555-5555-5555-5555-555555555555',
    'about',
    'teacher',
    '김보람 원장',
    '순수미술 서양화 전공 · 미술심리상담사 1급',
    '순수미술 서양화를 전공하고 아동·청소년 미술 수업을 지도하고 있습니다.\n\n표현 과정 중심의 수업을 바탕으로 아이들이 자신의 시선과 생각을 자연스럽게 작품으로 풀어낼 수 있도록 지도합니다.\n\n해외 연수와 교류 전시 경험을 수업에 반영하여 다양한 재료와 표현 접근을 안내합니다.',
    jsonb_build_object(
        'career', jsonb_build_array(
            '순수미술 서양화 전공 (4년제 졸업)',
            '미술심리상담사 1급',
            '러시아 이르쿠츠크 예술대학 연수',
            '한국·러시아 교류전 및 해외 전시 참여',
            '미술 공모전 다수 특선·입선',
            '아동·청소년 미술 지도 및 교습소 운영'
        )
    ),
    3,
    true
),
(
    '26666666-6666-6666-6666-666666666666',
    'about',
    'facilities',
    '교습소 시설',
    'Our Space',
    '라미아트 미술교습소의 수업 공간과 전시 공간을 소개합니다.',
    jsonb_build_object(
        'items', jsonb_build_array(
            jsonb_build_object(
                'title', '메인 교실',
                'description', '넓고 밝은 공간에서 다양한 미술 활동을 할 수 있는 메인 교실입니다. 최대 5명의 학생이 편안하게 수업을 받을 수 있습니다.',
                'imageUrl', 'https://qfhegmh38hapjycv.public.blob.vercel-storage.com/studio-outside.png??w=600&h=400&fit=crop'
            ),
            jsonb_build_object(
                'title', '작업실',
                'description', '입체 작업과 프로젝트를 위한 공간입니다. 다양한 재료와 도구를 활용한 창작 활동이 이루어집니다.',
                'imageUrl', 'https://qfhegmh38hapjycv.public.blob.vercel-storage.com/studio-inside.png??w=600&h=400&fit=crop'
            ),
            jsonb_build_object(
                'title', '미니 갤러리',
                'description', '학생들의 작품을 전시하는 공간입니다. 자신의 작품이 전시되는 경험을 통해 성취감과 자신감을 얻습니다.',
                'imageUrl', 'https://qfhegmh38hapjycv.public.blob.vercel-storage.com/mini_gallery.png?w=600&h=400&fit=crop'
            )
        )
    ),
    4,
    true
)
ON CONFLICT (page_key, section_key)
DO UPDATE SET
    title = EXCLUDED.title,
    subtitle = EXCLUDED.subtitle,
    content = EXCLUDED.content,
    extra_data = EXCLUDED.extra_data,
    sort_order = EXCLUDED.sort_order,
    is_active = EXCLUDED.is_active;

UPDATE rami_art_studio.classes
SET title = '유치부 미술 수업',
    age_range = '5-7세',
    description = '아동의 발달 단계에 맞춘 드로잉 중심 미술 수업으로, 자유로운 선과 색 표현을 통해 상상력과 창의력을 자연스럽게 키워나갑니다.',
    curriculum = '다양한 도구와 재료를 활용한 기초 드로잉 표현\n관찰을 바탕으로 한 선·형태·비율 연습\n손의 조절력과 집중력을 기르는 드로잉 트레이닝\n명암, 질감 표현을 통한 입체감과 색채 감각 이해\n일상과 자연을 주제로 한 주제별 드로잉 작업',
    thumbnail_url = '/images/design-mode/Gallery_08.png',
    sort_order = 1,
    is_active = true,
    deleted_at = NULL
WHERE age_group = 'kindergarten';

INSERT INTO rami_art_studio.classes (
    id,
    age_group,
    age_range,
    title,
    description,
    curriculum,
    thumbnail_url,
    sort_order,
    is_active
)
SELECT
    '31111111-1111-1111-1111-111111111111',
    'kindergarten',
    '5-7세',
    '유치부 미술 수업',
    '아동의 발달 단계에 맞춘 드로잉 중심 미술 수업으로, 자유로운 선과 색 표현을 통해 상상력과 창의력을 자연스럽게 키워나갑니다.',
    '다양한 도구와 재료를 활용한 기초 드로잉 표현\n관찰을 바탕으로 한 선·형태·비율 연습\n손의 조절력과 집중력을 기르는 드로잉 트레이닝\n명암, 질감 표현을 통한 입체감과 색채 감각 이해\n일상과 자연을 주제로 한 주제별 드로잉 작업',
    '/images/design-mode/Gallery_08.png',
    1,
    true
WHERE NOT EXISTS (
    SELECT 1 FROM rami_art_studio.classes WHERE age_group = 'kindergarten'
);

UPDATE rami_art_studio.classes
SET title = '초등부 미술 수업',
    age_range = '8-13세',
    description = '기초 드로잉부터 다양한 매체 활용까지, 체계적인 커리큘럼으로 미술의 기본기를 다지고 창의적 표현력을 키웁니다. 자신만의 생각과 감정을 자유롭게 표현할 수 있는 능력을 기릅니다.',
    curriculum = '기초 드로잉 및 채색 기법 학습\n다양한 미술 매체 경험 및 활용\n주제별 창작 활동 및 작품 제작\n미술사와 작가 탐구를 통한 예술 이해\n창의적 문제 해결 능력 향상 프로젝트',
    thumbnail_url = '/images/design-mode/Gallery_09.jpg',
    sort_order = 2,
    is_active = true,
    deleted_at = NULL
WHERE age_group = 'elementary';

INSERT INTO rami_art_studio.classes (
    id,
    age_group,
    age_range,
    title,
    description,
    curriculum,
    thumbnail_url,
    sort_order,
    is_active
)
SELECT
    '32222222-2222-2222-2222-222222222222',
    'elementary',
    '8-13세',
    '초등부 미술 수업',
    '기초 드로잉부터 다양한 매체 활용까지, 체계적인 커리큘럼으로 미술의 기본기를 다지고 창의적 표현력을 키웁니다. 자신만의 생각과 감정을 자유롭게 표현할 수 있는 능력을 기릅니다.',
    '기초 드로잉 및 채색 기법 학습\n다양한 미술 매체 경험 및 활용\n주제별 창작 활동 및 작품 제작\n미술사와 작가 탐구를 통한 예술 이해\n창의적 문제 해결 능력 향상 프로젝트',
    '/images/design-mode/Gallery_09.jpg',
    2,
    true
WHERE NOT EXISTS (
    SELECT 1 FROM rami_art_studio.classes WHERE age_group = 'elementary'
);

UPDATE rami_art_studio.classes
SET title = '중등부 미술 수업',
    age_range = '14-16세',
    description = '드로잉을 중심으로 다양한 표현 기법과 관찰, 구성 능력을 차근차근 익히며 자신만의 창의적 시각과 작품 세계를 넓힐 수 있도록 지도합니다. 단계별 학습을 통해 집중력과 표현력, 미술적 사고를 높이고 다양한 주제와 재료를 경험하며 자신감을 쌓을 수 있습니다.',
    curriculum = '드로잉과 다양한 표현 기법 심화 학습\n다양한 미술 사조 및 작가 연구\n개인 작품 제작과 포트폴리오 구성 경험\n현대 미술의 이해와 비평적 사고 함양',
    thumbnail_url = '/images/design-mode/Gallery_10.jpg',
    sort_order = 3,
    is_active = true,
    deleted_at = NULL
WHERE age_group = 'middle';

INSERT INTO rami_art_studio.classes (
    id,
    age_group,
    age_range,
    title,
    description,
    curriculum,
    thumbnail_url,
    sort_order,
    is_active
)
SELECT
    '33333333-3333-3333-3333-333333333333',
    'middle',
    '14-16세',
    '중등부 미술 수업',
    '드로잉을 중심으로 다양한 표현 기법과 관찰, 구성 능력을 차근차근 익히며 자신만의 창의적 시각과 작품 세계를 넓힐 수 있도록 지도합니다. 단계별 학습을 통해 집중력과 표현력, 미술적 사고를 높이고 다양한 주제와 재료를 경험하며 자신감을 쌓을 수 있습니다.',
    '드로잉과 다양한 표현 기법 심화 학습\n다양한 미술 사조 및 작가 연구\n개인 작품 제작과 포트폴리오 구성 경험\n현대 미술의 이해와 비평적 사고 함양',
    '/images/design-mode/Gallery_10.jpg',
    3,
    true
WHERE NOT EXISTS (
    SELECT 1 FROM rami_art_studio.classes WHERE age_group = 'middle'
);

DELETE FROM rami_art_studio.class_tags
WHERE class_id IN (
    SELECT id
    FROM rami_art_studio.classes
    WHERE age_group IN ('kindergarten', 'elementary', 'middle')
);

INSERT INTO rami_art_studio.class_tags (id, class_id, tag, sort_order)
SELECT gen_random_uuid(), c.id, tags.tag_value, tags.sort_order
FROM rami_art_studio.classes c
JOIN LATERAL (
    VALUES
        ('kindergarten', '기초 드로잉 표현', 1),
        ('kindergarten', '선·형태·비율 연습', 2),
        ('kindergarten', '손의 조절력과 집중력', 3),
        ('kindergarten', '명암과 질감 이해', 4),
        ('kindergarten', '주제별 드로잉 작업', 5),
        ('elementary', '기초 드로잉 및 채색', 1),
        ('elementary', '다양한 미술 매체', 2),
        ('elementary', '주제별 창작 활동', 3),
        ('elementary', '미술사와 작가 탐구', 4),
        ('elementary', '창의적 문제 해결', 5),
        ('middle', '표현 기법 심화', 1),
        ('middle', '미술 사조 및 작가 연구', 2),
        ('middle', '개인 작품 제작', 3),
        ('middle', '포트폴리오 구성', 4),
        ('middle', '비평적 사고 함양', 5)
) AS tags(age_group, tag_value, sort_order)
    ON c.age_group = tags.age_group;
