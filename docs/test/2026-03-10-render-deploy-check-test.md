# 테스트 대상
- Render 배포 서비스 헬스체크 및 버전 API
- Supabase PostgreSQL 스키마/데이터 존재 여부
- 배포 서비스 로그인 API

# 테스트 케이스 목록
- `GET https://rami-art-backend.onrender.com/api/v1/system/health`
- `GET https://rami-art-backend.onrender.com/api/v1/system/version`
- Supabase PostgreSQL에서 `rami_art_studio` 스키마/테이블/관리자 계정 조회
- `POST https://rami-art-backend.onrender.com/api/v1/auth/login`

# 실행 명령
```bash
curl -sS https://rami-art-backend.onrender.com/api/v1/system/health
curl -sS https://rami-art-backend.onrender.com/api/v1/system/version
curl -sS -X POST https://rami-art-backend.onrender.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"leedh84@gmail.com","password":"password123"}'
```

# 실제 결과 (성공/실패, 로그 요약)
- 헬스체크: 성공 (`status=UP`)
- 버전: 성공 (`0.0.1-SNAPSHOT`)
- DB 스키마/데이터: 성공
  - 스키마 존재
  - 테이블 11개 확인
  - `admin_users` 2건 확인
- 로그인 API: 실패
  - 응답: `{"success":false,"data":null,"message":null,"error":{"code":"INTERNAL_SERVER_ERROR","message":"서버 내부 오류가 발생했습니다."}}`

# 테스트 데이터 기준
- Render URL: `https://rami-art-backend.onrender.com`
- DB: Supabase PostgreSQL
- 테스트 관리자 계정: `leedh84@gmail.com`

# 이슈 및 조치 사항
- 배포본은 살아 있으나 로그인 API가 500을 반환한다.
- Render 로그와 환경변수(JWT/DB/profile) 점검이 필요하다.
