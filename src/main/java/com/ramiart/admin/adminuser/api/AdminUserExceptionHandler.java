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
        var status = "ADMIN_ACCOUNT_READ_DENIED".equals(exception.code()) ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure(exception.code(), "관리자 계정 요청을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
}
