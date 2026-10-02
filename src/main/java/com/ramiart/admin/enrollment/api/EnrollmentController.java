package com.ramiart.admin.enrollment.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.enrollment.application.EnrollmentException;
import com.ramiart.admin.enrollment.application.EnrollmentModels.*;
import com.ramiart.admin.enrollment.application.EnrollmentService;
import com.ramiart.admin.enrollment.application.EnrollmentService.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/enrollments")
public final class EnrollmentController {
    private final EnrollmentService service;
    public EnrollmentController(EnrollmentService service){this.service=service;}

    @GetMapping ResponseEntity<ApiEnvelope<CasePage>> list(@RequestParam(required=false)String statuses,
            @RequestParam(required=false)UUID courseId,@RequestParam(required=false)Instant from,
            @RequestParam(required=false)Instant to,@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="20")int size,HttpServletRequest request){return ok(service.list(statuses,courseId,from,to,page,size),request);}
    @GetMapping("/{id}") ResponseEntity<ApiEnvelope<CaseDetail>> detail(@PathVariable UUID id,HttpServletRequest request){return ok(service.detail(id),request);}
    @PostMapping ResponseEntity<ApiEnvelope<CaseDetail>> create(@RequestHeader("Idempotency-Key")UUID key,
            @RequestBody CaseCreate body,Authentication auth,HttpServletRequest request){return created(service.create(body,actor(auth),key,meta(request)),request);}
    @PostMapping("/{id}/activities") ResponseEntity<ApiEnvelope<CaseDetail>> contact(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key")UUID key,@RequestBody ActivityWrite body,Authentication auth,HttpServletRequest request){return created(service.contact(id,body,actor(auth),key,meta(request)),request);}
    @PostMapping("/{id}/trial") ResponseEntity<ApiEnvelope<CaseDetail>> trial(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key")UUID key,@RequestBody TrialWrite body,Authentication auth,HttpServletRequest request){return created(service.trial(id,body,actor(auth),key,meta(request)),request);}
    @PostMapping("/{id}/waitlist") ResponseEntity<ApiEnvelope<CaseDetail>> waitlist(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key")UUID key,@RequestBody WaitlistWrite body,Authentication auth,HttpServletRequest request){return created(service.waitlist(id,body,actor(auth),key,meta(request)),request);}
    @PostMapping("/{id}/lost") ResponseEntity<ApiEnvelope<CaseDetail>> lost(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key")UUID key,@RequestBody LostWrite body,Authentication auth,HttpServletRequest request){return created(service.lost(id,body,actor(auth),key,meta(request)),request);}
    @PostMapping("/{id}/enrollment-preview") ResponseEntity<ApiEnvelope<EnrollmentPreview>> preview(@PathVariable UUID id,
            @RequestBody EnrollWrite body,HttpServletRequest request){return ok(service.preview(id,body),request);}
    @PostMapping("/{id}/enroll") ResponseEntity<ApiEnvelope<EnrollmentResult>> enroll(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key")UUID key,@RequestBody EnrollWrite body,Authentication auth,HttpServletRequest request){return created(service.enroll(id,body,actor(auth),key,meta(request)),request);}

    @ExceptionHandler(EnrollmentException.class)
    ResponseEntity<?> error(EnrollmentException exception,HttpServletRequest request){HttpStatus status=switch(exception.code()){
        case "ENROLLMENT_NOT_FOUND","INQUIRY_NOT_FOUND","CLASS_GROUP_NOT_FOUND"->HttpStatus.NOT_FOUND;
        case "ENROLLMENT_CASE_EXISTS","ENROLLMENT_VERSION_CONFLICT","ENROLLMENT_CAPACITY_FULL","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;
        case "ENROLLMENT_CONSENT_REQUIRED","ENROLLMENT_DUPLICATE_CONFIRMATION_REQUIRED","ENROLLMENT_INVALID_TRANSITION","ENROLLMENT_PREVIEW_REQUIRED","ENROLLMENT_SLOT_INVALID"->HttpStatus.UNPROCESSABLE_ENTITY;
        default->HttpStatus.BAD_REQUEST;};Map<String,Object> value=new LinkedHashMap<>();value.put("code",exception.code());value.put("message",message(exception.code()));if(!exception.details().isEmpty())value.put("details",exception.details());return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("success",false,"error",value,"requestId",RequestIdFilter.get(request)));}

    private static String message(String code){return switch(code){case "ENROLLMENT_CASE_EXISTS"->"이미 연결된 등록 상담이 있습니다.";case "ENROLLMENT_VERSION_CONFLICT"->"다른 관리자가 먼저 변경했습니다.";case "ENROLLMENT_CAPACITY_FULL"->"반 정원이 마감되었습니다.";case "ENROLLMENT_CONSENT_REQUIRED"->"필수 동의가 필요합니다.";case "ENROLLMENT_DUPLICATE_CONFIRMATION_REQUIRED"->"중복 후보 확인이 필요합니다.";case "ENROLLMENT_INVALID_TRANSITION"->"현재 단계에서 허용되지 않는 변경입니다.";case "ENROLLMENT_PREVIEW_REQUIRED"->"등록 영향을 다시 확인해 주세요.";default->"입력값을 확인해 주세요.";};}
    private static UUID actor(Authentication value){return UUID.fromString(value.getName());}
    private static RequestMetadata meta(HttpServletRequest request){String ua=request.getHeader(HttpHeaders.USER_AGENT);if(ua!=null)ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua!=null&&ua.length()>512)ua=ua.substring(0,512);return new RequestMetadata(RequestIdFilter.get(request),request.getRemoteAddr(),ua);}
    private static <T>ResponseEntity<ApiEnvelope<T>> ok(T data,HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));}
    private static <T>ResponseEntity<ApiEnvelope<T>> created(T data,HttpServletRequest request){return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(data,RequestIdFilter.get(request)));}
}
