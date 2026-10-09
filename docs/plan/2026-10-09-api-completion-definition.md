# Remaining administrator API functionality — 2026-10-09

This definition was derived from the current controllers, services, database constraints and frontend API client. Older completion labels are not evidence of implementation.

## Scope and acceptance

1. Retention: implement the seven `/api/admin/retention` endpoints used by `lib/api/retention.ts`. GET and preview require RETENTION_READ; hold changes and execution require RETENTION_EXECUTE. Execution additionally requires the current database OWNER role and a session-bound, single-use RETENTION_EXECUTION reauthentication token. Unsafe methods retain Origin validation. Responses use ApiEnvelope, requestId and no-store.
2. Consent evidence: add POST `/api/admin/students/{studentId}/consent-evidence-assets` (multipart file, UUID Idempotency-Key, CONSENT_WRITE). Accept sanitized JPEG/PNG/WebP using the existing image validator and 10 MiB limit. Store only in the private evidence bucket; return asset metadata without public URL or storage key. Existing consent collection and signed URL access use this asset. Duplicate keys replay; changed content conflicts. Failed persistence compensates storage upload. Unused uploads expire after seven days and cleanup respects references and legal holds.
3. Data transfer: preserve existing CSV schemas and domain confirmation behavior. Persist encrypted queue inputs and return queued jobs; background parser/export processing survives restart, claims one job with database locks, and clears queued plaintext-derived inputs after completion. Export uses the exact anonymized snapshot approved by its short-lived preview. Expiry removes files before clearing database payloads; failures retain retryable state. Active legal holds block cleanup. Download URL behavior remains private and expiring.
4. Notification: existing queue API and outbox worker remain. EMAIL uses the existing public-site supplier Resend through a dedicated backend adapter. SMS and KAKAO supplier selection is pending user input. Do not invent a supplier or treat an empty provider list as successful delivery.

## Retention behavior

Domains: INQUIRY (existing retention_expires_at), STUDENT_PRIVATE (five years after latest terminal status), CONSENT_EVIDENCE (five years after revocation/expiry), TRANSFER_FILE (job expires_at), NOTIFICATION_PAYLOAD (one year after terminal completion). Cutoff is required, cannot be in the future and cannot override these eligibility conditions. This implements repository business retention rules, without making legal determinations.

Registry: INQUIRY, STUDENT, STUDENT_CONSENT, DATA_TRANSFER_JOB, NOTIFICATION_MESSAGE. Hold reason is 10–500 characters, optional endsAt must be future. Hold identity/history is immutable. Expired holds cease blocking and transition to EXPIRED. Creation validates target existence; duplicate active target conflicts. Release requires a valid reason and an ACTIVE hold. Hold lists use size 1–100 and UUID cursors with filters.

Preview stores aggregate counts and a digest, never candidate identifiers in API/audit output. It expires after 24 hours. Execution checks policy, cutoff, fingerprint, legal holds and references again. Inquiry linked to enrollment is excluded; student financial or still-retained operational references block private purge; referenced/shared evidence assets are excluded. Purge retains aggregate run/audit history and minimal surrogate identities, removes relevant ciphertext/hash/storage objects, and does not bypass database immutability guards. Storage deletion precedes database changes and is repeatable for missing objects. Per-item failures preserve retryable rows and aggregate safe error counts; completed items are not counted twice. PARTIAL/FAILED retries require a new request key and fresh OWNER reauthentication. Same request key returns the existing run without consuming another token.

## Validation

Tests must cover permission boundaries, hold expiry/release, preview drift and expiry, owner reauthentication, request replay/conflict, reference exclusions, storage failure/retry, payload purge and worker restart/claim behavior. Use isolated PostgreSQL with canonical migrations and fake storage/providers; production data is not a test fixture.

## Implementation steps

- [x] Retention contract, services/repository, controller/security and PostgreSQL tests.
- [x] Private evidence upload/storage/cleanup and behavioral tests.
- [x] Durable transfer queue/parser/export/expiry and behavioral tests.
- [x] EMAIL Resend adapter and isolated HTTP contract tests.
- [ ] SMS/KAKAO adapters: provider contracts must be supplied.
- [x] Backend regression/build and documentation reconciliation; existing administrator replay failures isolated.
- [x] Standalone backend source preparation and 24 functional tests.
- [ ] New API production migration/publication: pending migration authorization and Render workspace confirmation.

## 새 기능 정의와 구현 경계

| 기능 | HTTP 계약 | 처리 기준 |
| --- | --- | --- |
| 보존 정책 | GET `/api/admin/retention/policies` | 정책과 최근 실행 집계 조회 |
| 보존 요청 | GET/POST `/api/admin/retention/holds` | 대상 존재 확인, 중복 ACTIVE 금지, 만료 자동 반영 |
| 보존 해제 | POST `/api/admin/retention/holds/{id}/release` | ACTIVE 요청만 해제, 사유 필수 |
| 파기 미리보기 | POST `/api/admin/retention/previews` | domain/cutoffAt 필수, 최대 10,000 후보, 집계만 반환 |
| 파기 승인 | POST `/api/admin/retention/runs` | Idempotency-Key, OWNER 재인증, confirmation `파기 실행`, 202 |
| 실행 조회 | GET `/api/admin/retention/runs/{id}` | 처리·제외·실패 건수와 안전한 오류 코드 |
| 증빙 업로드 | POST `/api/admin/students/{studentId}/consent-evidence-assets` | multipart file, 학생별 귀속, 201, URL 미노출 |
| CSV 가져오기 | 기존 POST `/api/admin/data-transfer/imports` | 201 PARSING → 작업자 검증 → READY/FAILED |
| CSV 내보내기 | 기존 POST `/api/admin/data-transfer/exports` | 201 PROCESSING → 승인된 익명 스냅샷 파일 생성 → COMPLETED/FAILED |

파기 실행은 현재 DB의 ACTIVE OWNER를 확인하고 세션 쿠키에 묶인 RETENTION_EXECUTION 재인증을 소비한다. 승인 후 후보 내용·보존 요청·참조가 변경되면 작업자가 FAILED로 종료하고 새 미리보기를 요구한다. 작업은 트랜잭션당 100개까지 진행하며 완료된 건수는 재시도에도 유지한다. 학생 개인정보 파기는 연결된 청구·출석·기록·메모·전환·감사 이력이 있으면 보수적으로 제외한다. 이러한 참조까지 삭제하는 별도 정책은 이번 구현에 포함하지 않는다.

CSV 헤더/인코딩 오류는 업로드 응답이 아니라 작업 결과의 FAILED/errorCode로 확인한다. 정상 행만 기존 확정 API로 적용할 수 있다. 파일 저장 실패는 최대 5회 재시도하며 만료 정리는 보존 요청을 확인하고 파일 삭제 후 암호화 입력·행 페이로드·중복 해시·원본 파일명을 제거한다.

### EMAIL 설정

기본 비활성화. `ADMIN_NOTIFICATION_RESEND_ENABLED=true`, `RESEND_API_KEY`, `ADMIN_NOTIFICATION_FROM_EMAIL`을 운영 설정에 등록해야 EMAIL 공급자가 활성화된다. Spring property는 `admin.notification.resend.enabled/api-key/from`이다. 발신 주소는 공급자에서 허용된 주소를 사용해야 한다. SMS/KAKAO는 공급자 계약이 없어 구현 완료로 표시하지 않는다.

Resend에는 HTML이 아닌 text 본문을 보내고 메시지 UUID로 동일한 Idempotency-Key를 사용한다. 429/5xx·연결 실패·공급자의 동시 처리 잠금 409는 재시도 가능, 인증이나 멱등 키 본문 충돌 등은 영구 실패로 분류한다. 성공 응답에 공급자 메시지 ID가 없으면 SENT로 확정하지 않는다. 공급자 멱등 키 보존 기간은 24시간이므로 이 구현은 무기한 중복 방지를 보장하지 않는다. 근거: [Send Email](https://resend.com/docs/api-reference/emails/send-email), [Errors](https://resend.com/docs/api-reference/errors), [Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys).

### 운영 반영 선행 조건

`supabase/migrations/202610090001_api_completion.sql` 적용이 필요하다. 로컬 테스트는 격리된 PostgreSQL에서 migration을 검증하며 운영 DB에는 적용하지 않는다. 비공개 Supabase 저장소와 이메일 자격증명이 없는 환경에서는 실제 파일 저장·메일 발송을 완료로 판단하지 않는다.

## 검증 결과 — 2026-10-09

- 마지막 기능/HTTP 테스트: 25개 통과, 실패 0. `./gradlew test --tests '*RetentionIntegrationTest' --tests '*PrivateEvidenceIntegrationTest' --tests '*TransferQueueIntegrationTest' --tests '*ResendNotificationProviderTest' --tests '*AuthPersistenceIntegrationTest.retentionHttp*' assemble`.
- 보존 5개 영역의 실제 삭제/암호화 페이로드 제거, hold 해제/만료, 승인 후 drift, 24시간 preview 만료, 101개 배치의 누적 건수, 저장소 실패 후 재시도를 확인했다.
- HTTP 세션/Origin/OWNER 재인증/재인증 단일 사용/멱등 replay를 확인했다. 저장소와 공급자 호출은 테스트 대역이며 운영 호출 성공을 뜻하지 않는다.
- 전체 `./gradlew build`: 199개 중 195개 통과, 4개 실패. 실패는 기존 관리자 계정 생성·역할·상태·잠금 해제의 최초 응답과 replay 응답의 +09:00/Z 표현 불일치다. 변경 전 HEAD 사본에서 해당 관리자 테스트 5개 중 동일한 4개 실패를 재현했다. 이 기능 개발에서 관리자 계정 코드는 변경하지 않았다. compile/bootJar/jar/assemble은 실행 성공했고 마지막 scoped assemble도 성공했다.
- 프런트 `bun run build` 통과. 수정한 4개 프런트/테스트 파일의 focused ESLint 통과. `bun run lint`는 기존 `.worktrees/` 및 Windows 경로 복사본의 생성 파일까지 탐색해 실패했으며 이번 변경 파일의 lint 오류는 없었다. 기존 lint 설정은 변경하지 않았다.
- Chromium CSV 화면 테스트 2개 통과: PARSING 이후 정상 행 표시, PROCESSING 중 다운로드 차단 및 COMPLETED 조회 후 허용. `tests/e2e/admin/data-transfer.spec.ts`.
- `git diff --check` 통과. 운영 DB migration/실제 증빙 저장·서명 URL/이메일 발송은 미검증. SMS·KAKAO 공급자 연결은 미구현.

## master 배포 및 재동기화

사용자가 요청한 기준선 배포/재동기화에서 기존 커밋 `a16aa2a`의 Vercel production READY와 `www.ramiartstudio.com` alias를 확인했다. master push/pull은 최신 상태였고 `git pull --ff-only`로 재동기화했다. 별도 백엔드 저장소의 `c817af9` 소스는 이 커밋의 backend tree와 동일했다. 진행 중인 이 문서의 새 API 변경은 기준선 배포에 포함되지 않는다. Render 운영 상태와 새 API migration/배포는 별도 확인 대상이다.

별도 backend 저장소 사본에서도 24개 새 기능 테스트가 통과했다. 테스트는 standalone root 또는 monorepo parent의 supabase 경로를 찾고 숫자로 시작하는 migration만 적용한다. 기존 `supabase/migrations/seed.sql`을 migration으로 실행하지 않고 `supabase/seed.sql` fixture를 사용한다. 기존 supabase tree와 Git 이력은 보존했다. 새 API 코드는 로컬 master `13ac725`에 커밋했으며 운영 DB 적용 전 원격 배포는 보류했다.
