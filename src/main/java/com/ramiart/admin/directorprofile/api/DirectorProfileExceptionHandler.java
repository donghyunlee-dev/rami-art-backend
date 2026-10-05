package com.ramiart.admin.directorprofile.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.directorprofile.application.DirectorProfileException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = DirectorProfileController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DirectorProfileExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectorProfileExceptionHandler.class);
    @ExceptionHandler(DirectorProfileException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(DirectorProfileException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            case "DIRECTOR_PROFILE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DIRECTOR_PROFILE_VERSION_CONFLICT", "DIRECTOR_PROFILE_DRAFT_EXISTS",
                    "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "DIRECTOR_PROFILE_INVALID", "DIRECTOR_PROFILE_NOT_PUBLISHABLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.FORBIDDEN;
        };
        List<ApiEnvelope.FieldError> fields = exception.field() == null ? List.of()
                : List.of(new ApiEnvelope.FieldError(exception.field(), exception.code(), "입력값을 확인해 주세요."));
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure(exception.code(), "원장 프로필 요청을 처리하지 못했습니다.", fields,
                        RequestIdFilter.get(request)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception, HttpServletRequest request) {
        LOGGER.error("Director profile persistence failed: requestId={}, method={}, path={}",
                RequestIdFilter.get(request), request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure("DIRECTOR_PROFILE_SAVE_FAILED", "원장 프로필 요청을 처리하지 못했습니다.",
                        List.of(), RequestIdFilter.get(request)));
    }
}
