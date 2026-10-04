package com.ramiart.admin.sitebrand.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.sitebrand.application.SiteBrandException;
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

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SiteBrandExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SiteBrandExceptionHandler.class);
    @ExceptionHandler(SiteBrandException.class) ResponseEntity<ApiEnvelope<Void>> handle(SiteBrandException ex,HttpServletRequest request) {
        HttpStatus status=switch(ex.code()) {
            case "SITE_BRAND_VALIDATION_FAILED" -> HttpStatus.BAD_REQUEST;
            case "SITE_BRAND_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SITE_BRAND_VERSION_CONFLICT","SITE_BRAND_DRAFT_EXISTS","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "SITE_BRAND_CONTRAST_FAILED","SITE_BRAND_MEDIA_NOT_READY" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore()).body(
                ApiEnvelope.failure(ex.code(),"사이트 브랜드 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception,HttpServletRequest request) {
        LOGGER.error("Site brand persistence failed: requestId={}, method={}, path={}",
                RequestIdFilter.get(request),request.getMethod(),request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure("SITE_BRAND_PERSISTENCE_FAILED","사이트 브랜드 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
