# 작업 맥락
- 요청: Render 배포 주소(`https://rami-art-backend.onrender.com`)의 서비스 상태와 DB 데이터를 확인.

# 변경된 파일 목록
- 코드 변경 없음

# 핵심 변경 내용
- 공개 엔드포인트 확인:
  - `GET /api/v1/system/health` 정상
  - `GET /api/v1/system/version` 정상
- DB 확인:
  - `rami_art_studio` 스키마 존재
  - 핵심 테이블 존재
  - 관리자 계정 2건 존재
- 배포 서비스 기능 확인:
  - `POST /api/v1/auth/login` 호출 시 `INTERNAL_SERVER_ERROR` 발생

# 리스크/후속 작업
- Render 서비스는 기동 자체는 정상이나, 로그인 API는 현재 배포본에서 장애 상태다.
- Render 환경변수/배포된 코드 버전/서버 로그 확인이 필요하다.
