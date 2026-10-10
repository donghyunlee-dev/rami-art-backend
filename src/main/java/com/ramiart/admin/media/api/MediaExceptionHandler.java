package com.ramiart.admin.media.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.media.application.MediaException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = MediaController.class)
public final class MediaExceptionHandler {
    @ExceptionHandler(MediaException.class)
    ResponseEntity<ApiEnvelope<Void>> media(MediaException exception, HttpServletRequest request) {
        Definition definition = definition(exception.code());
        return ResponseEntity.status(definition.status()).body(ApiEnvelope.failure(
                exception.code(), definition.message(), List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiEnvelope<Void>> missing(MissingServletRequestPartException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiEnvelope.failure("MEDIA_FILE_REQUIRED", "이미지를 선택해 주세요.",
                List.of(), RequestIdFilter.get(request)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiEnvelope<Void>> tooLarge(MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiEnvelope.failure(
                "MEDIA_FILE_TOO_LARGE", "이미지는 10MB 이하여야 합니다.", List.of(), RequestIdFilter.get(request)));
    }

    private static Definition definition(String code) {
        return switch (code) {
            case "MEDIA_WRITE_DENIED" -> new Definition(HttpStatus.FORBIDDEN, "이미지 사용처 조회 권한이 없습니다.");
            case "MEDIA_FILE_REQUIRED" -> new Definition(HttpStatus.BAD_REQUEST, "이미지를 선택해 주세요.");
            case "MEDIA_FILE_TOO_LARGE" -> new Definition(HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 10MB 이하여야 합니다.");
            case "MEDIA_TYPE_NOT_SUPPORTED" -> new Definition(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "JPEG, PNG, WebP 이미지만 업로드할 수 있습니다.");
            case "MEDIA_DIMENSION_INVALID" -> new Definition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "이미지 크기는 최대 8000×8000px입니다.");
            case "MEDIA_FILE_CORRUPTED" -> new Definition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "손상된 이미지입니다.");
            case "MEDIA_SECURITY_REJECTED" -> new Definition(HttpStatus.UNPROCESSABLE_ENTITY,
                    "안전성 검사를 통과하지 못했습니다.");
            case "MEDIA_SCAN_TIMEOUT" -> new Definition(HttpStatus.SERVICE_UNAVAILABLE,
                    "이미지 검사가 지연되고 있습니다. 다시 시도해 주세요.");
            case "MEDIA_ASSET_NOT_FOUND" -> new Definition(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다.");
            case "MEDIA_ASSET_IN_USE", "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_REQUEST_PROCESSING" ->
                    new Definition(HttpStatus.CONFLICT, switch (code) {
                        case "MEDIA_ASSET_IN_USE" -> "사용 중인 이미지입니다.";
                        case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키가 다른 이미지에 사용되었습니다.";
                        default -> "동일한 요청을 처리하고 있습니다.";
                    });
            default -> new Definition(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 저장소를 사용할 수 없습니다.");
        };
    }

    private record Definition(HttpStatus status, String message) {
    }
}
