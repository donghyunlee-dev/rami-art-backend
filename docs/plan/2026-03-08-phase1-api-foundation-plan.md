# 단계 계획: Phase 1 API Foundation

## 목표
- API 공통 응답 포맷(`success`, `data`, `message`, `error`)을 코드에 반영
- 전역 예외 처리 규칙을 구현해 일관된 오류 응답 제공
- 시스템 엔드포인트를 공통 포맷으로 정렬

## 작업 단계
1. 공통 DTO 정의 (`ApiResponse`, `ApiError`, `ErrorCode`)
2. 전역 예외 처리기 추가 (`GlobalExceptionHandler`)
3. 시스템 컨트롤러 응답 형식 통일
4. 빌드/테스트 실행
5. 결과 문서화 (`docs/result`, `docs/test`)

## 완료 기준
- 시스템 API가 공통 성공 응답 포맷으로 반환
- 예외 발생 시 공통 오류 응답 포맷으로 반환
- 테스트 실행 결과 기록 완료
