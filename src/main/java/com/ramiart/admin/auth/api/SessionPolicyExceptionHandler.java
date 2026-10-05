package com.ramiart.admin.auth.api;

import com.ramiart.admin.auth.application.SessionPolicyService.SessionPolicyException;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = SessionPolicyController.class)
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public final class SessionPolicyExceptionHandler {
    @ExceptionHandler(SessionPolicyException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(SessionPolicyException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "SECURITY_POLICY_READ_DENIED", "SECURITY_POLICY_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "ACTIVE_SESSION_POLICY_MISSING" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "SESSION_POLICY_CURSOR_INVALID", "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            case "SESSION_POLICY_CHANGED", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore()).body(
                ApiEnvelope.failure(exception.code(), "세션 보안 정책을 조회하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
}
