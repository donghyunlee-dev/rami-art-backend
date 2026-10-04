package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.TuitionBillingService;
import com.ramiart.admin.tuition.application.TuitionBillingService.BillingException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tuition-billings")
public final class TuitionBillingController {
    private final TuitionBillingService service;
    public TuitionBillingController(TuitionBillingService service) { this.service=service; }

    @GetMapping
    ResponseEntity<ApiEnvelope<TuitionBillingService.BillingList>> list(
            @RequestParam(required=false) String yearMonth, @RequestParam(required=false) String statuses,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            Authentication authentication, HttpServletRequest request) {
        List<String> statusList = statuses==null || statuses.isBlank() ? List.of() : Arrays.stream(statuses.split(",",-1)).toList();
        return ok(service.list(yearMonth,statusList,page,size,authentication),request);
    }

    @GetMapping("/{billingId}")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> detail(@PathVariable UUID billingId,
            Authentication authentication,HttpServletRequest request) {
        return ok(service.detail(billingId,authentication),request);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));
    }

    @ExceptionHandler(BillingException.class)
    ResponseEntity<ApiEnvelope<Void>> error(BillingException exception,HttpServletRequest request) {
        HttpStatus status=switch(exception.code()) {
            case "TUITION_BILLING_READ_DENIED", "TUITION_BILLING_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "TUITION_BILLING_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(
                exception.code(),"수업료 청구를 조회할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
