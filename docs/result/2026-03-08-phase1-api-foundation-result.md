# 작업 맥락
- 요청: 다음 개발 단계 진행.
- 수행 범위: Phase 1(API Foundation)로 공통 응답 포맷, 전역 예외 처리, 시스템 API 응답 정렬 적용.

# 변경된 파일 목록
- `src/main/java/com/rami/artstudio/common/api/ApiResponse.java`
- `src/main/java/com/rami/artstudio/common/api/ApiError.java`
- `src/main/java/com/rami/artstudio/common/api/ErrorCode.java`
- `src/main/java/com/rami/artstudio/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/rami/artstudio/system/SystemController.java`
- `src/test/resources/application.yml`
- `README.md`

# 핵심 변경 내용
- 공통 응답 DTO 추가:
  - 성공: `success`, `data`, `message`
  - 실패: `success=false`, `error.code`, `error.message`
- 전역 예외 처리기 추가:
  - Validation, Bad Request, Not Found, Internal Server Error 처리
- 시스템 API 응답 형식 통일:
  - `/api/v1/system/health`
  - `/api/v1/system/version`

# 리스크/후속 작업
- 현재 오류 응답은 단일 메시지 중심이며 필드별 상세 오류 포맷은 추가 확장 필요.
- 다음 단계(Auth API)에서 인증/인가 예외(`401`, `403`) 매핑을 Security와 함께 보강 필요.
