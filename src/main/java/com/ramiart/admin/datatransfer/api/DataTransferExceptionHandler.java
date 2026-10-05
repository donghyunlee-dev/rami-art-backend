package com.ramiart.admin.datatransfer.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.datatransfer.application.DataTransferService.DataTransferException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = DataTransferController.class)
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public final class DataTransferExceptionHandler {
    @ExceptionHandler(DataTransferException.class)
    ResponseEntity<ApiEnvelope<Void>> handle(DataTransferException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "DATA_TRANSFER_READ_DENIED", "DATA_TRANSFER_IMPORT_DENIED" -> HttpStatus.FORBIDDEN;
            case "TRANSFER_JOB_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TRANSFER_FILE_DUPLICATED" -> HttpStatus.CONFLICT;
            case "TRANSFER_FILE_EXPIRED" -> HttpStatus.GONE;
            default -> exception.code().endsWith("_DENIED") ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(ApiEnvelope.failure(exception.code(), "데이터 이관 요청을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
}
