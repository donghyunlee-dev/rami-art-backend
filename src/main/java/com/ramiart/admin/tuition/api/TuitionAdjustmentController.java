package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.TuitionAdjustmentService;
import com.ramiart.admin.tuition.application.TuitionAdjustmentService.*;
import com.ramiart.admin.tuition.infrastructure.JdbcTuitionAdjustmentRepository.TuitionAdjustmentException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/tuition")
public final class TuitionAdjustmentController {
    private final TuitionAdjustmentService service;
    public TuitionAdjustmentController(TuitionAdjustmentService service) { this.service=service; }

    @GetMapping("/billings/{billingId}/adjustments")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> history(@PathVariable UUID billingId,Authentication auth,HttpServletRequest request) {
        return ok(service.history(billingId,auth),request);
    }
    @PostMapping("/billings/{billingId}/adjustment-preview")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> preview(@PathVariable UUID billingId,@RequestBody PreviewRequest body,Authentication auth,HttpServletRequest request) {
        return ok(service.preview(billingId,body,auth),request);
    }
    @PostMapping("/billings/{billingId}/adjustments")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> adjust(@PathVariable UUID billingId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody AdjustmentRequest body,Authentication auth,HttpServletRequest request) {
        return created(service.adjust(billingId,body,key,metadata(request),auth),request);
    }
    @PostMapping("/adjustments/{adjustmentId}/cancellation")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> cancelAdjustment(@PathVariable UUID adjustmentId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CancelRequest body,Authentication auth,HttpServletRequest request) {
        return ok(service.cancelAdjustment(adjustmentId,body,key,metadata(request),auth),request);
    }
    @PostMapping("/billings/{billingId}/refunds")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> refund(@PathVariable UUID billingId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody RefundRequest body,Authentication auth,HttpServletRequest request) {
        return created(service.refund(billingId,body,key,metadata(request),auth),request);
    }
    @PostMapping("/refunds/{refundId}/cancellation")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> cancelRefund(@PathVariable UUID refundId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CancelRequest body,Authentication auth,HttpServletRequest request) {
        return ok(service.cancelRefund(refundId,body,key,metadata(request),auth),request);
    }
    private static Metadata metadata(HttpServletRequest request) {
        String agent=request.getHeader("User-Agent"); if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]",""); if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);
        return new Metadata(RequestIdFilter.get(request),request.getRemoteAddr(),agent);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> created(T data,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));
    }
    @ExceptionHandler(TuitionAdjustmentException.class)
    ResponseEntity<ApiEnvelope<Void>> error(TuitionAdjustmentException exception,HttpServletRequest request) {
        String code=exception.code();
        HttpStatus status=switch(code) {
            case "TUITION_ADJUSTMENT_READ_DENIED","TUITION_ADJUSTMENT_WRITE_DENIED","TUITION_REFUND_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "TUITION_BILLING_NOT_FOUND","TUITION_PAYMENT_NOT_FOUND","TUITION_ADJUSTMENT_NOT_FOUND","TUITION_REFUND_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TUITION_ADJUSTMENT_VERSION_CONFLICT","TUITION_ADJUSTMENT_ALREADY_CANCELLED","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "TUITION_CHARGE_NEGATIVE","TUITION_REFUND_EXCEEDS_CREDIT","TUITION_ADJUSTMENT_REFUND_REQUIRED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "TUITION_ADJUSTMENT_SAVE_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,
                "수업료 조정 또는 환불을 처리할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
