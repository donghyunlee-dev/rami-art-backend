package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.TuitionPaymentService;
import com.ramiart.admin.tuition.application.TuitionPaymentService.CancelRequest;
import com.ramiart.admin.tuition.application.TuitionPaymentService.CreateRequest;
import com.ramiart.admin.tuition.application.TuitionPaymentService.Metadata;
import com.ramiart.admin.tuition.application.TuitionPaymentService.TuitionPaymentException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public final class TuitionPaymentController {
    private final TuitionPaymentService service;
    public TuitionPaymentController(TuitionPaymentService service) { this.service=service; }

    @GetMapping("/tuition-billings/{billingId}/payments")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> list(@PathVariable UUID billingId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size,
            Authentication auth,HttpServletRequest request) {
        return ok(service.list(billingId,cursor,size,auth),request);
    }
    @PostMapping("/tuition-billings/{billingId}/payments")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> create(@PathVariable UUID billingId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CreateRequest body,Authentication auth,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.create(billingId,body,key,metadata(request),auth),RequestIdFilter.get(request)));
    }
    @PostMapping("/tuition-payments/{paymentId}/cancellations")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> cancel(@PathVariable UUID paymentId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CancelRequest body,Authentication auth,HttpServletRequest request) {
        return ok(service.cancel(paymentId,body,key,metadata(request),auth),request);
    }
    private static Metadata metadata(HttpServletRequest request) {
        String agent=request.getHeader("User-Agent"); if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);
        return new Metadata(RequestIdFilter.get(request),request.getRemoteAddr(),agent);
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));
    }
    @ExceptionHandler(TuitionPaymentException.class)
    ResponseEntity<ApiEnvelope<Void>> error(TuitionPaymentException exception,HttpServletRequest request) {
        String code=exception.code();
        HttpStatus status=switch(code) {
            case "TUITION_PAYMENT_READ_DENIED","TUITION_PAYMENT_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "TUITION_BILLING_NOT_FOUND","TUITION_PAYMENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "BILLING_VERSION_CONFLICT","PAYMENT_VERSION_CONFLICT","PAYMENT_ALREADY_CANCELLED","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "PAYMENT_EXCEEDS_BALANCE","BILLING_NOT_PAYABLE","TUITION_PAYMENT_HAS_REFUND" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "PAYMENT_SAVE_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,
                "수업료 납입을 처리할 수 없습니다.",List.of(),RequestIdFilter.get(request)));
    }
}
