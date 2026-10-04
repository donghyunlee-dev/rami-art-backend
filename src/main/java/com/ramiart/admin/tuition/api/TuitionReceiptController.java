package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.TuitionReceiptService;
import com.ramiart.admin.tuition.application.TuitionReceiptService.IssueRequest;
import com.ramiart.admin.tuition.application.TuitionReceiptService.Metadata;
import com.ramiart.admin.tuition.application.TuitionReceiptService.ReissueRequest;
import com.ramiart.admin.tuition.infrastructure.JdbcTuitionReceiptRepository.TuitionReceiptException;
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
public final class TuitionReceiptController {
    private final TuitionReceiptService service;
    public TuitionReceiptController(TuitionReceiptService service){this.service=service;}
    @GetMapping("/payments/{paymentId}/receipt")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> get(@PathVariable UUID paymentId,Authentication auth,HttpServletRequest request){return ok(service.getByPayment(paymentId,auth),request);}
    @PostMapping("/payments/{paymentId}/receipt")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> issue(@PathVariable UUID paymentId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody IssueRequest body,Authentication auth,HttpServletRequest request){return created(service.issue(paymentId,body,key,metadata(request),auth),request);}
    @PostMapping("/receipts/{receiptId}/versions")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> reissue(@PathVariable UUID receiptId,@RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ReissueRequest body,Authentication auth,HttpServletRequest request){return created(service.reissue(receiptId,body,key,metadata(request),auth),request);}
    @GetMapping("/receipts/{receiptId}/versions/{version}/download-url")
    ResponseEntity<ApiEnvelope<Map<String,Object>>> download(@PathVariable UUID receiptId,@PathVariable int version,Authentication auth,HttpServletRequest request){
        return ok(service.downloadUrl(receiptId,version,metadata(request),auth),request);
    }
    private static Metadata metadata(HttpServletRequest request){String agent=request.getHeader("User-Agent");if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);
        return new Metadata(RequestIdFilter.get(request),request.getRemoteAddr(),agent);}
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T body,HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(body,RequestIdFilter.get(request)));}
    private static <T> ResponseEntity<ApiEnvelope<T>> created(T body,HttpServletRequest request){return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(body,RequestIdFilter.get(request)));}
    @ExceptionHandler(TuitionReceiptException.class)
    ResponseEntity<ApiEnvelope<Void>> error(TuitionReceiptException exception,HttpServletRequest request){String code=exception.code();HttpStatus status=switch(code){
        case "TUITION_RECEIPT_READ_DENIED","TUITION_RECEIPT_ISSUE_DENIED"->HttpStatus.FORBIDDEN;
        case "TUITION_PAYMENT_NOT_FOUND","RECEIPT_NOT_FOUND","RECEIPT_VERSION_NOT_FOUND"->HttpStatus.NOT_FOUND;
        case "RECEIPT_ALREADY_EXISTS","RECEIPT_VERSION_CONFLICT","RECEIPT_GENERATION_IN_PROGRESS","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;
        case "RECEIPT_PAYMENT_NOT_CONFIRMED"->HttpStatus.UNPROCESSABLE_ENTITY;
        case "RECEIPT_FILE_INTEGRITY_FAILED","RECEIPT_GENERATION_FAILED","RECEIPT_STORAGE_UNAVAILABLE"->HttpStatus.INTERNAL_SERVER_ERROR;
        default->HttpStatus.BAD_REQUEST;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,"수업료 영수증을 처리할 수 없습니다.",List.of(),RequestIdFilter.get(request)));}
}
