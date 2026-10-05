package com.ramiart.admin.audit.api;

import com.ramiart.admin.audit.application.AuditService.AuditException;
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

@RestControllerAdvice(basePackageClasses = AuditController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AuditExceptionHandler {
    @ExceptionHandler(AuditException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(AuditException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "AUDIT_READ_DENIED" -> HttpStatus.FORBIDDEN;
            case "AUDIT_LOG_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "AUDIT_PERIOD_INVALID", "AUDIT_FILTER_INVALID", "AUDIT_CURSOR_INVALID" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure(exception.code(), "감사 로그 요청을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
}
