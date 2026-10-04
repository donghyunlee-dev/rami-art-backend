package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.TuitionBillingBatchService;
import com.ramiart.admin.tuition.application.TuitionBillingBatchService.RequestMetadata;
import com.ramiart.admin.tuition.application.TuitionBillingService.BillingException;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tuition-billings")
public final class TuitionBillingBatchController {
    private final TuitionBillingBatchService service;
    public TuitionBillingBatchController(TuitionBillingBatchService service) { this.service=service; }

    @PostMapping("/batches")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> issue(@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody TuitionBillingBatchService.Request body,Authentication authentication,HttpServletRequest request) {
        return ok(service.issue(body,key,metadata(request),authentication),request);
    }

    @GetMapping("/batches/{batchId}")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> batch(@PathVariable UUID batchId,Authentication authentication,HttpServletRequest request) {
        return ok(service.find(batchId,authentication),request);
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        String agent=request.getHeader("User-Agent");
        if(agent!=null) agent=agent.replaceAll("[\\p{Cntrl}]","");
        if(agent!=null&&agent.length()>512) agent=agent.substring(0,512);
        return new RequestMetadata(RequestIdFilter.get(request),request.getRemoteAddr(),agent);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));
    }

    @ExceptionHandler(BillingException.class)
    ResponseEntity<ApiEnvelope<Void>> error(BillingException exception,HttpServletRequest request) {
        HttpStatus status=switch(exception.code()) {
            case "TUITION_BILLING_READ_DENIED","TUITION_BILLING_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "TUITION_BILLING_BATCH_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "BILLING_PREVIEW_CHANGED","IDEMPOTENCY_KEY_REUSED" -> HttpStatus.CONFLICT;
            case "BILLING_PREVIEW_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(
                exception.code(),"수업료 청구 배치를 처리할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
