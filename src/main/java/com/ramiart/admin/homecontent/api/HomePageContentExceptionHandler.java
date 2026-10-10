package com.ramiart.admin.homecontent.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.homecontent.application.HomePageContentException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = HomePageContentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HomePageContentExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(HomePageContentExceptionHandler.class);
    @ExceptionHandler(HomePageContentException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(HomePageContentException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "HOME_CONTENT_READ_DENIED", "HOME_CONTENT_WRITE_DENIED", "HOME_CONTENT_PUBLISH_DENIED" -> HttpStatus.FORBIDDEN;
            case "HOME_CONTENT_PUBLIC_NOT_FOUND", "HOME_CONTENT_REVISION_NOT_FOUND", "HOME_CONTENT_DRAFT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "HOME_CONTENT_VERSION_CONFLICT", "HOME_CONTENT_DRAFT_EXISTS", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "HOME_CONTENT_NOT_PUBLISHABLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(
                exception.code(), "홈페이지 콘텐츠 요청을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception, HttpServletRequest request) {
        LOGGER.error("Home page content persistence failed: requestId={}, method={}, path={}", RequestIdFilter.get(request), request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(
                "HOME_CONTENT_PERSISTENCE_FAILED", "홈페이지 콘텐츠 요청을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
}
