package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.TuitionBillingService;
import com.ramiart.admin.tuition.application.TuitionBillingService.BillingException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tuition-billing-previews")
public final class TuitionBillingPreviewController {
    private final TuitionBillingService service;
    public TuitionBillingPreviewController(TuitionBillingService service) { this.service=service; }

    @GetMapping("/{yearMonth}")
    ResponseEntity<ApiEnvelope<TuitionBillingService.BillingPreview>> preview(@PathVariable String yearMonth,
            Authentication authentication,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(
                service.preview(yearMonth,authentication),RequestIdFilter.get(request)));
    }

    @ExceptionHandler(BillingException.class)
    ResponseEntity<ApiEnvelope<Void>> error(BillingException exception,HttpServletRequest request) {
        HttpStatus status=switch(exception.code()) {
            case "TUITION_BILLING_READ_DENIED" -> HttpStatus.FORBIDDEN;
            case "BILLING_PREVIEW_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(
                exception.code(),"수업료 청구 미리보기를 조회할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
