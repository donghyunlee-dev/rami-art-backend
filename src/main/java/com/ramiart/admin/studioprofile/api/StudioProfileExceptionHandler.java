package com.ramiart.admin.studioprofile.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.studioprofile.application.StudioProfileException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(basePackageClasses = StudioProfileController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StudioProfileExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(StudioProfileExceptionHandler.class);
    @ExceptionHandler(StudioProfileException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(StudioProfileException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            case "STUDIO_PROFILE_NOT_FOUND", "PUBLIC_STUDIO_PROFILE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "STUDIO_PROFILE_VERSION_CONFLICT", "STUDIO_PROFILE_DRAFT_EXISTS",
                    "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "STUDIO_PROFILE_CONTACT_REQUIRED", "STUDIO_PROFILE_LOCATION_INVALID",
                    "STUDIO_PROFILE_HOURS_INVALID", "STUDIO_PROFILE_NOT_PUBLISHABLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.FORBIDDEN;
        };
        List<ApiEnvelope.FieldError> fields = exception.field() == null ? List.of()
                : List.of(new ApiEnvelope.FieldError(exception.field(), exception.code(), "입력값을 확인해 주세요."));
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore()).body(
                ApiEnvelope.failure(exception.code(), "교습소 프로필 요청을 처리하지 못했습니다.", fields,
                        RequestIdFilter.get(request)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception, HttpServletRequest request) {
        LOGGER.error("Studio profile persistence failed: requestId={}, method={}, path={}",
                RequestIdFilter.get(request), request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure("STUDIO_PROFILE_SAVE_FAILED", "교습소 프로필 요청을 처리하지 못했습니다.",
                        List.of(), RequestIdFilter.get(request)));
    }
}
