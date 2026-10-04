package com.ramiart.admin.blog.api;

import com.ramiart.admin.blog.application.BlogException;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
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
public final class BlogExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlogExceptionHandler.class);
    @ExceptionHandler(BlogException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(BlogException exception,HttpServletRequest request) {
        String code=exception.code();
        HttpStatus status=code.contains("NOT_FOUND")?HttpStatus.NOT_FOUND
                :code.contains("CONFLICT")||code.startsWith("IDEMPOTENCY_")?HttpStatus.CONFLICT
                :code.startsWith("BLOG_CONTENT_")||code.equals("BLOG_NOT_PUBLISHABLE")||code.equals("BLOG_MEDIA_NOT_READY")
                        ?HttpStatus.UNPROCESSABLE_ENTITY
                        :code.endsWith("PERSISTENCE_FAILED")||code.endsWith("SAVE_FAILED")
                                ?HttpStatus.INTERNAL_SERVER_ERROR:HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiEnvelope.failure(code,"요청을 처리할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException exception,HttpServletRequest request) {
        LOGGER.error("Blog persistence failed: requestId={}, method={}, path={}",
                RequestIdFilter.get(request),request.getMethod(),request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiEnvelope.failure("BLOG_PERSISTENCE_FAILED","블로그 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
