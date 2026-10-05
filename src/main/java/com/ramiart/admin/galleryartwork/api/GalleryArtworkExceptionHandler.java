package com.ramiart.admin.galleryartwork.api;

import com.ramiart.admin.galleryartwork.application.GalleryArtworkException;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses=GalleryArtworkController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GalleryArtworkExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(GalleryArtworkExceptionHandler.class);
    @ExceptionHandler(GalleryArtworkException.class) ResponseEntity<ApiEnvelope<Void>> handle(GalleryArtworkException e,HttpServletRequest r){HttpStatus s=switch(e.code()){case "VALIDATION_ERROR"->HttpStatus.BAD_REQUEST;case "GALLERY_NOT_FOUND"->HttpStatus.NOT_FOUND;case "GALLERY_VERSION_CONFLICT","GALLERY_DRAFT_EXISTS","GALLERY_FEATURED_ORDER_CONFLICT","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;case "GALLERY_NOT_PUBLISHABLE","GALLERY_COURSE_INVALID","GALLERY_CONSENT_REQUIRED","GALLERY_MEDIA_INVALID"->HttpStatus.UNPROCESSABLE_ENTITY;default->HttpStatus.FORBIDDEN;};List<ApiEnvelope.FieldError> f=e.field()==null?List.of():List.of(new ApiEnvelope.FieldError(e.field(),e.code(),"입력값을 확인해 주세요."));return ResponseEntity.status(s).cacheControl(org.springframework.http.CacheControl.noStore()).body(ApiEnvelope.failure(e.code(),"갤러리 작품 요청을 처리하지 못했습니다.",f,RequestIdFilter.get(r)));}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException e,HttpServletRequest r){LOG.error("Gallery artwork persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(r),r.getMethod(),r.getRequestURI());return ResponseEntity.status(500).cacheControl(org.springframework.http.CacheControl.noStore()).body(ApiEnvelope.failure("GALLERY_SAVE_FAILED","갤러리 작품 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(r)));}
}
