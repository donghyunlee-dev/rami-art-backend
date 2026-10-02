# ☕ 관리자 Backend

> Java 21, Spring Boot 4.1.1, Gradle 9.7.1 기반 관리자 전용 API다.

## 🚀 실행 준비

Supabase 로컬 DB를 먼저 실행한 뒤 환경 변수를 설정한다. 기본값은 Supabase CLI의 로컬 PostgreSQL 포트와 계정을 사용한다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `ADMIN_DATABASE_URL` | `jdbc:postgresql://127.0.0.1:54322/postgres` | PostgreSQL JDBC URL |
| `ADMIN_DATABASE_USERNAME` | `postgres` | DB 사용자 |
| `ADMIN_DATABASE_PASSWORD` | `postgres` | DB 비밀번호 |
| `ADMIN_DATABASE_POOL_SIZE` | `10` | 최대 DB 연결 수 |
| `PORT` | `9000` | Render가 주입하는 운영 HTTP 포트 |
| `ADMIN_BACKEND_PORT` | `9000` | 로컬에서 `PORT`가 없을 때 사용할 HTTP 포트 |
| `ADMIN_ALLOWED_ORIGIN` | `http://localhost:3000` | 상태 변경 요청에 허용할 정확한 Frontend origin |
| `ADMIN_STAFF_PHONE_KEY` | 로컬 개발 키 | 직원 연락처 AES-256-GCM에 사용할 32바이트 Base64 키 |
| `ADMIN_INQUIRY_DATA_KEY` | 로컬 개발 키 | 문의 연락처 AES-256-GCM에 사용할 독립 32바이트 Base64 키 |
| `ADMIN_STUDENT_DATA_KEY` | 로컬 개발 키 | 원생·보호자 개인정보 AES-256-GCM에 사용할 독립 32바이트 Base64 키 |
| `ADMIN_MEDIA_STORAGE_ROOT` | `build/media-storage` | 검증을 통과한 로컬 미디어 파일 저장 경로 |
| `ADMIN_MEDIA_PUBLIC_BASE_URL` | `http://localhost:8080` | `/media/...` 공개 URL을 만들 기준 주소 |

```powershell
npm run supabase -- start
npm run supabase -- db reset
cd backend
.\gradlew.bat bootRun
```

Docker가 없는 Windows 개발 PC에서는 격리된 PostgreSQL 17 실행기를 사용할 수 있다. 첫 터미널에서 DB를 유지하고 두 번째 터미널에서 Backend를 실행한다.

```powershell
cd backend
.\gradlew.bat localDatabase

# 새 터미널
cd backend
$env:SPRING_PROFILES_ACTIVE = "local"
.\gradlew.bat bootRun
```

`localDatabase`는 매번 새 임시 DB에 현재 migration과 seed를 순서대로 적용한다. 원격 Supabase 프로젝트를 수정하지 않으며 프로세스를 종료하면 데이터도 제거된다. 포트 충돌 시 `LOCAL_ADMIN_DB_PORT`와 `ADMIN_DATABASE_URL`을 같은 값으로 변경한다.

로컬 seed 관리자 계정은 `owner@rami.local`이며 임시 비밀번호는 `LocalOnly!Change123`이다. 로컬 개발 전용이고 최초 로그인 후 변경하도록 강제된다.

## 🧪 검증

```powershell
cd backend
.\gradlew.bat test
```

인증 통합 테스트는 Docker 없이 임베디드 PostgreSQL 17에서 실제 migration과 seed를 적용한다. 로그인·비밀번호 변경의 commit/rollback과 JDBC 매핑을 함께 검증한다.

seed 반복 실행 시 변경된 비밀번호 보존, anon/authenticated 권한 회수와 RLS의 행 접근 차단, 주요 DB 제약 위반 거부도 검증한다. 테스트는 임시 PostgreSQL만 초기화하며 외부 DB 접속 설정을 사용하지 않는다. Supabase CLI 전체 스택 검증은 별도 완료 항목이다.

Wrapper 배포본은 공식 SHA-256 체크섬으로 검증한다. Java 21이 없으면 Toolchain Resolver가 호환 JDK를 자동 공급한다.

## 🔐 보안 경계

- 브라우저는 원본 세션 토큰을 HttpOnly cookie로만 보관한다.
- DB에는 세션 토큰의 SHA-256 해시만 저장한다.
- 관리자 테이블은 RLS를 활성화하고 Supabase `anon`, `authenticated` 역할 접근을 차단한다.
- 운영 환경에서는 seed의 로컬 계정과 비밀번호를 사용하지 않는다.
- 운영 환경은 `ADMIN_STAFF_PHONE_KEY`, `ADMIN_INQUIRY_DATA_KEY`, `ADMIN_STUDENT_DATA_KEY`를 각각 독립 생성해 비밀 관리 저장소에서 주입하고 로컬 기본 키를 사용하지 않는다. 키 교체 시에는 기존 ciphertext 재암호화 절차가 필요하다.
- 로컬 미디어 저장소는 개발·검증용이다. 운영에서는 영속 볼륨 또는 객체 저장소, 악성 파일 검사, CDN 공개 주소와 미사용 자산 정리 작업을 별도로 구성한다.

새 비밀번호는 `{pbkdf2-sha256-600k}` 형식(PBKDF2-HMAC-SHA256, 600,000회, salt 16바이트)으로 저장한다. 기존 BCrypt 해시는 검증을 지원하며 비밀번호 변경 시 새 형식으로 전환한다. 기존 BCrypt에는 UTF-8 72바이트를 초과한 입력을 거부해 잘림이나 예외를 방지한다. 새 형식은 기존 12~128 길이 계약과 한글·이모지를 지원한다. 길이는 현재 Frontend·Bean Validation과 같은 UTF-16 코드 단위이고 공백 제거·유니코드 정규화를 수행하지 않는다.

`20260914000100_admin_auth_rate_limit.sql`을 적용한 뒤 새 Backend를 배포한다. 요청 제한은 15분 고정 창이며 성공·실패 모두 포함한다. 로그인은 IP 100회·정규화 이메일 10회, 비밀번호 변경은 IP 30회·인증 사용자 5회다. 초과 시 기존 API 계약의 `TOO_MANY_REQUESTS` 또는 `PASSWORD_CHANGE_RATE_LIMITED`와 `Retry-After`를 반환한다. 카운터는 별도 트랜잭션에 커밋되고 다중 인스턴스가 DB에서 공유한다. 만료 후 하루가 지난 행은 다음 제한 검사에서 정리한다.

DB에는 제한 키의 SHA-256만 저장한다. 비밀번호·세션 토큰은 제한 키로 사용하지 않는다. SHA-256 키는 원문 저장 방지 목적이며 익명화를 보장하지 않는다. 전달 헤더는 기본적으로 무시하고 실제 접속 IP로 제한한다. Next.js·로드밸런서 뒤에서는 프록시 IP를 공유하므로 운영 배포 전에 신뢰 프록시 설정 및 실제 사용자 IP 전달을 검증해야 한다.

설계 근거: [Spring PBKDF2 API](https://docs.spring.io/spring-security/reference/7.0/api/java/org/springframework/security/crypto/password/Pbkdf2PasswordEncoder.html), [OWASP 비밀번호 저장 지침](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). JDK에서 제공하는 PBKDF2를 사용해 새 암호화 라이브러리 의존성 없이 긴 입력을 처리한다.

## 🔌 인증 세션 API

| Method | 경로 | 책임 |
|---|---|---|
| `POST` | `/api/admin/auth/sessions` | 로그인, 실패 누적·잠금, 세션 발급 |
| `GET` | `/api/admin/auth/sessions/current` | 현재 사용자·역할·권한·만료 문맥 조회 |
| `PATCH` | `/api/admin/auth/sessions/current` | 발급 정책 범위에서 유휴 만료 연장 |
| `DELETE` | `/api/admin/auth/sessions/current` | 세션 폐기와 브라우저 쿠키 제거 |
| `PUT` | `/api/admin/users/me/password` | 본인 비밀번호 변경과 다른 세션 폐기 |

상태 변경 요청은 `ADMIN_ALLOWED_ORIGIN`과 정확히 같은 `Origin`만 허용한다. 인증 쿠키에는 `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`와 절대 만료 `Expires`를 적용한다.
