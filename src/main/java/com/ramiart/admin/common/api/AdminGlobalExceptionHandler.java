package com.ramiart.admin.common.api;

import com.ramiart.admin.auth.application.AuthSessionException;
import com.ramiart.admin.auth.application.AuthRateLimitException;
import com.ramiart.admin.course.application.CourseException;
import com.ramiart.admin.staff.application.StaffException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AdminGlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminGlobalExceptionHandler.class);

    @ExceptionHandler(AuthRateLimitException.class)
    ResponseEntity<ApiEnvelope<Void>> handleRateLimit(AuthRateLimitException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .body(ApiEnvelope.failure(exception.getMessage(), "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                        List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(AuthSessionException.class)
    ResponseEntity<ApiEnvelope<Void>> handleAuthSession(
            AuthSessionException exception, HttpServletRequest request) {
        ErrorDefinition definition = definition(exception.code());
        return ResponseEntity.status(definition.status())
                .body(ApiEnvelope.failure(
                        exception.code(), definition.message(), List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(CourseException.class)
    ResponseEntity<ApiEnvelope<Void>> handleCourse(CourseException exception, HttpServletRequest request) {
        ErrorDefinition definition = courseDefinition(exception.code());
        return ResponseEntity.status(definition.status()).body(ApiEnvelope.failure(
                exception.code(), definition.message(), List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(StaffException.class)
    ResponseEntity<ApiEnvelope<Void>> handleStaff(StaffException exception, HttpServletRequest request) {
        ErrorDefinition definition = staffDefinition(exception.code());
        return ResponseEntity.status(definition.status()).body(ApiEnvelope.failure(
                exception.code(), definition.message(), List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiEnvelope<Void>> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiEnvelope.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiEnvelope.FieldError(
                        error.getField(), "INVALID_VALUE", safeValidationMessage(error.getDefaultMessage())))
                .toList();
        boolean invalidExtension = fieldErrors.stream().anyMatch(error -> "action".equals(error.field()));
        boolean invalidNewPassword = fieldErrors.stream().anyMatch(error -> "newPassword".equals(error.field()));
        String code = invalidExtension
                ? "SESSION_EXTENSION_INVALID"
                : invalidNewPassword ? "PASSWORD_POLICY_VIOLATION" : "VALIDATION_ERROR";
        HttpStatus status = invalidExtension || invalidNewPassword
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiEnvelope.failure(
                code, "입력값을 확인해 주세요.", fieldErrors, RequestIdFilter.get(request)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ApiEnvelope<Void>> handleBadRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiEnvelope.failure(
                "VALIDATION_ERROR", "요청 형식을 확인해 주세요.", List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> handleDataAccess(
            DataAccessException exception, HttpServletRequest request) {
        String requestId = RequestIdFilter.get(request);
        if (request.getRequestURI().startsWith("/api/admin/staff")) {
            String detail = rootMessage(exception);
            String code = detail.contains("staff_profile_staff_code_key")
                    ? "STAFF_CODE_DUPLICATED"
                    : detail.contains("staff_profile_phone_hash_key")
                            ? "STAFF_PHONE_DUPLICATED"
                            : detail.contains("uq_staff_profile_admin_user")
                                    ? "STAFF_ADMIN_ALREADY_LINKED"
                                    : detail.contains("ex_class_staff_lead_period")
                                            || detail.contains("ex_class_staff_same_assignment_period")
                                            ? "STAFF_ASSIGNMENT_OVERLAP"
                                            : detail.contains("ck_class_staff_assignment_owner_period")
                                                    ? "STAFF_PERIOD_OUTSIDE_EMPLOYMENT"
                                                    : detail.contains("ck_staff_profile_assignment_period")
                                                            ? "STAFF_FUTURE_ASSIGNMENT_EXISTS"
                                                            : detail.contains("ck_staff_profile_code_immutable")
                                                                    ? "STAFF_CODE_IMMUTABLE"
                                                                    : detail.contains("ck_staff_profile_active_admin_user")
                                                                            ? "STAFF_ADMIN_INACTIVE"
                                                                            : "STAFF_PERSISTENCE_FAILED";
            LOGGER.error("Staff database request failed: requestId={}, method={}, path={}, code={}",
                    requestId, request.getMethod(), request.getRequestURI(), code);
            ErrorDefinition definition = staffDefinition(code);
            return ResponseEntity.status(definition.status()).body(ApiEnvelope.failure(
                    code, definition.message(), List.of(), requestId));
        }
        LOGGER.error("Database request failed: requestId={}, method={}, path={}",
                requestId, request.getMethod(), request.getRequestURI(), exception);
        boolean courseRequest = request.getRequestURI().startsWith("/api/admin/courses")
                || request.getRequestURI().startsWith("/api/admin/class-groups");
        if (courseRequest) {
            String detail = rootMessage(exception);
            boolean deletingClassGroup = "DELETE".equals(request.getMethod())
                    && request.getRequestURI().startsWith("/api/admin/class-groups/");
            String code = deletingClassGroup && detail.contains("foreign key constraint")
                    ? "CLASS_GROUP_REFERENCED"
                    : detail.contains("course_code_key") || detail.contains("class_group_code_key")
                    ? "COURSE_CODE_DUPLICATED"
                    : detail.contains("uq_course_active_display_order")
                            ? "COURSE_DISPLAY_ORDER_DUPLICATED"
                            : detail.contains("ck_course_has_no_active_groups")
                                    ? "COURSE_HAS_ACTIVE_GROUPS" : "COURSE_PERSISTENCE_FAILED";
            ErrorDefinition definition = courseDefinition(code);
            return ResponseEntity.status(definition.status()).body(ApiEnvelope.failure(
                    code, definition.message(), List.of(), requestId));
        }
        boolean logout = "DELETE".equals(request.getMethod())
                && "/api/admin/auth/sessions/current".equals(request.getRequestURI());
        boolean passwordChange = "PUT".equals(request.getMethod())
                && "/api/admin/users/me/password".equals(request.getRequestURI());
        HttpStatus status = logout ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        String code = logout
                ? "AUTH_SESSION_REVOKE_FAILED"
                : passwordChange ? "PASSWORD_CHANGE_FAILED" : "AUTH_SESSION_CREATE_FAILED";
        return ResponseEntity.status(status).body(ApiEnvelope.failure(
                code, "인증 저장소를 사용할 수 없습니다.", List.of(), requestId));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected request failure: requestId={}, method={}, path={}",
                RequestIdFilter.get(request), request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiEnvelope.failure(
                "INTERNAL_SERVER_ERROR",
                "요청 처리 중 오류가 발생했습니다.",
                List.of(),
                RequestIdFilter.get(request)));
    }

    private static String safeValidationMessage(String message) {
        return message == null ? "올바른 값을 입력해 주세요." : message;
    }

    private static ErrorDefinition definition(String code) {
        return switch (code) {
            case "AUTHENTICATION_FAILED" -> new ErrorDefinition(
                    HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요.");
            case "TEMPORARY_PASSWORD_EXPIRED" -> new ErrorDefinition(
                    HttpStatus.UNAUTHORIZED, "임시 비밀번호가 만료되었습니다. 관리자에게 재발급을 요청해 주세요.");
            case "ACCOUNT_LOCKED" -> new ErrorDefinition(
                    HttpStatus.LOCKED, "로그인 시도가 제한되었습니다. 잠시 후 다시 시도해 주세요.");
            case "SESSION_REQUIRED" -> new ErrorDefinition(
                    HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
            case "SESSION_EXPIRED" -> new ErrorDefinition(
                    HttpStatus.UNAUTHORIZED, "세션이 만료되었습니다. 다시 로그인해 주세요.");
            case "SESSION_REVOKED" -> new ErrorDefinition(
                    HttpStatus.UNAUTHORIZED, "종료된 세션입니다. 다시 로그인해 주세요.");
            case "ADMIN_ACCESS_DENIED" -> new ErrorDefinition(
                    HttpStatus.FORBIDDEN, "사용 가능한 관리자 메뉴가 없습니다.");
            case "PASSWORD_CHANGE_REQUIRED" -> new ErrorDefinition(
                    HttpStatus.FORBIDDEN, "비밀번호를 먼저 변경해 주세요.");
            case "SESSION_NOT_EXTENDABLE" -> new ErrorDefinition(
                    HttpStatus.CONFLICT, "현재 세션을 더 이상 연장할 수 없습니다.");
            case "SESSION_EXTENSION_INVALID" -> new ErrorDefinition(
                    HttpStatus.UNPROCESSABLE_ENTITY, "세션 연장 요청을 확인해 주세요.");
            case "CURRENT_PASSWORD_INVALID" -> new ErrorDefinition(
                    HttpStatus.UNAUTHORIZED, "현재 비밀번호가 올바르지 않습니다.");
            case "PASSWORD_POLICY_VIOLATION" -> new ErrorDefinition(
                    HttpStatus.UNPROCESSABLE_ENTITY, "새 비밀번호 정책을 확인해 주세요.");
            case "PASSWORD_COMPROMISED" -> new ErrorDefinition(
                    HttpStatus.UNPROCESSABLE_ENTITY, "널리 알려진 유출 비밀번호는 사용할 수 없습니다.");
            case "PASSWORD_REUSED" -> new ErrorDefinition(
                    HttpStatus.UNPROCESSABLE_ENTITY, "최근 사용한 비밀번호는 다시 사용할 수 없습니다.");
            case "ADMIN_USER_VERSION_CONFLICT" -> new ErrorDefinition(
                    HttpStatus.CONFLICT, "계정 정보가 변경되었습니다. 다시 시도해 주세요.");
            default -> new ErrorDefinition(
                    HttpStatus.INTERNAL_SERVER_ERROR, "인증 요청을 처리하지 못했습니다.");
        };
    }

    private static ErrorDefinition courseDefinition(String code) {
        return switch (code) {
            case "COURSE_NOT_FOUND" -> new ErrorDefinition(HttpStatus.NOT_FOUND, "과정을 찾을 수 없습니다.");
            case "CLASS_GROUP_NOT_FOUND" -> new ErrorDefinition(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다.");
            case "COURSE_CODE_DUPLICATED" -> new ErrorDefinition(HttpStatus.CONFLICT, "이미 사용 중인 코드입니다.");
            case "COURSE_DISPLAY_ORDER_DUPLICATED" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "운영 중인 다른 과정이 같은 표시 순서를 사용하고 있습니다.");
            case "COURSE_VERSION_CONFLICT" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "다른 관리자가 먼저 변경했습니다. 최신 정보를 다시 확인해 주세요.");
            case "IDEMPOTENCY_KEY_REUSED" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "같은 요청 키가 다른 내용에 사용되었습니다.");
            case "IDEMPOTENCY_IN_PROGRESS" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "동일한 요청을 처리하고 있습니다. 잠시 후 다시 확인해 주세요.");
            case "COURSE_HAS_ACTIVE_GROUPS" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "운영 중인 반이 있어 과정을 비활성화할 수 없습니다.");
            case "CLASS_CAPACITY_BELOW_OCCUPANCY" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "현재 또는 예정된 등록·보강 인원보다 정원을 줄일 수 없습니다.");
            case "CLASS_GROUP_NOT_DRAFT" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "준비 상태의 반만 삭제할 수 있습니다.");
            case "CLASS_GROUP_REFERENCED" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "시간표, 배정 또는 운영 기록에서 참조 중인 반은 삭제할 수 없습니다.");
            case "COURSE_INACTIVE" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "비활성 과정에는 반을 만들 수 없습니다.");
            case "COURSE_QUERY_INVALID", "COURSE_VALIDATION_ERROR" -> new ErrorDefinition(
                    HttpStatus.BAD_REQUEST, "과정 또는 반 입력값을 확인해 주세요.");
            case "COURSE_PERSISTENCE_FAILED" -> new ErrorDefinition(HttpStatus.INTERNAL_SERVER_ERROR,
                    "과정 정보를 저장하지 못했습니다.");
            default -> new ErrorDefinition(HttpStatus.INTERNAL_SERVER_ERROR, "과정 요청을 처리하지 못했습니다.");
        };
    }

    private static ErrorDefinition staffDefinition(String code) {
        return switch (code) {
            case "STAFF_NOT_FOUND" -> new ErrorDefinition(HttpStatus.NOT_FOUND, "직원 정보를 찾을 수 없습니다.");
            case "STAFF_CODE_DUPLICATED" -> new ErrorDefinition(HttpStatus.CONFLICT, "이미 사용 중인 직원 코드입니다.");
            case "STAFF_PHONE_DUPLICATED" -> new ErrorDefinition(HttpStatus.CONFLICT, "이미 등록된 연락처입니다.");
            case "STAFF_ADMIN_ALREADY_LINKED" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "해당 관리자 계정은 다른 직원에게 연결되어 있습니다.");
            case "STAFF_ASSIGNMENT_OVERLAP" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "같은 기간에 이미 지정된 담당과 겹칩니다.");
            case "STAFF_VERSION_CONFLICT" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "다른 관리자가 먼저 변경했습니다. 최신 정보를 다시 확인해 주세요.");
            case "IDEMPOTENCY_KEY_REUSED" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "같은 요청 키가 다른 내용에 사용되었습니다.");
            case "IDEMPOTENCY_IN_PROGRESS" -> new ErrorDefinition(HttpStatus.CONFLICT,
                    "동일한 요청을 처리하고 있습니다. 잠시 후 다시 확인해 주세요.");
            case "STAFF_PERIOD_OUTSIDE_EMPLOYMENT" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "담당 기간은 직원 근무 기간과 반 운영 기간 안이어야 합니다.");
            case "STAFF_FUTURE_ASSIGNMENT_EXISTS" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "종료되지 않은 담당 반이 있습니다. 퇴사일에 맞춰 함께 종료해 주세요.");
            case "STAFF_ASSIGNMENT_HISTORY_IMMUTABLE" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "종료된 담당 이력은 삭제할 수 없습니다.");
            case "STAFF_INACTIVE" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "퇴사한 직원에게 담당 반을 지정할 수 없습니다.");
            case "STAFF_CODE_IMMUTABLE" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "발급된 직원 코드는 변경할 수 없습니다.");
            case "STAFF_ADMIN_INACTIVE" -> new ErrorDefinition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "활성 관리자 계정만 연결할 수 있습니다.");
            case "STAFF_QUERY_INVALID", "STAFF_VALIDATION_ERROR" -> new ErrorDefinition(
                    HttpStatus.BAD_REQUEST, "직원 또는 담당 입력값을 확인해 주세요.");
            case "STAFF_PERSISTENCE_FAILED" -> new ErrorDefinition(HttpStatus.INTERNAL_SERVER_ERROR,
                    "직원 정보를 저장하지 못했습니다.");
            default -> new ErrorDefinition(HttpStatus.INTERNAL_SERVER_ERROR, "직원 요청을 처리하지 못했습니다.");
        };
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private record ErrorDefinition(HttpStatus status, String message) {
    }
}
