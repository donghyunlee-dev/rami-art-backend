package com.ramiart.admin.adminuser.api;

import com.ramiart.admin.adminuser.application.AdminUserService.AdminUserException;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AdminUserController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AdminUserExceptionHandler {
    @ExceptionHandler(AdminUserException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(AdminUserException exception, HttpServletRequest request) {
        var status = switch (exception.code()) {
            case "ADMIN_ACCOUNT_READ_DENIED", "ADMIN_ACCOUNT_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "ADMIN_USER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ADMIN_USER_CREATE_FAILED", "ADMIN_USER_UPDATE_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "ADMIN_EMAIL_DUPLICATED" -> HttpStatus.CONFLICT;
            case "ADMIN_ROLE_INVALID", "SELF_DEACTIVATION_DENIED", "ADMIN_ROLE_UNCHANGED",
                    "ADMIN_STATUS_TRANSITION_DENIED", "ADMIN_USER_NOT_LOCKED", "INACTIVE_USER_UNLOCK_DENIED",
                    "SELF_TEMP_PASSWORD_ISSUE_DENIED", "INACTIVE_USER_TEMP_PASSWORD_DENIED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "ADMIN_USER_VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS",
                    "SENSITIVE_IDEMPOTENCY_RESPONSE_CONSUMED", "LAST_OWNER_REQUIRED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure(exception.code(), "관리자 계정 요청을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
}
