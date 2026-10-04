package com.ramiart.admin.tuition.api;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.tuition.application.TuitionPolicyModels.*;
import com.ramiart.admin.tuition.application.TuitionPolicyService;
import com.ramiart.admin.tuition.application.TuitionPolicyService.Metadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/tuition-policies")
public class TuitionPolicyController {
    private static final Logger LOGGER=LoggerFactory.getLogger(TuitionPolicyController.class);
    private final TuitionPolicyService service;
    public TuitionPolicyController(TuitionPolicyService service){this.service=service;}
    @GetMapping("/{year}") ResponseEntity<ApiEnvelope<Policy>> get(@PathVariable int year,@RequestParam(defaultValue="PUBLISHED") String mode,HttpServletRequest r){return ok(service.get(year,mode),r);}
    @GetMapping("/{year}/assignment-options") ResponseEntity<ApiEnvelope<AssignmentOptions>> options(@PathVariable int year,HttpServletRequest r){return ok(service.options(year),r);}
    @PostMapping("/{year}/drafts") ResponseEntity<ApiEnvelope<Policy>> create(@PathVariable int year,@RequestHeader("Idempotency-Key") UUID key,Authentication auth,HttpServletRequest r){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.create(year,actor(auth),key,meta(r)),RequestIdFilter.get(r)));}
    @PutMapping("/{year}/drafts/{draftId}") ResponseEntity<ApiEnvelope<Policy>> save(@PathVariable int year,@PathVariable UUID draftId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Write body,Authentication auth,HttpServletRequest r){return ok(service.save(year,draftId,body,actor(auth),key,meta(r)),r);}
    @PostMapping("/{year}/publications") ResponseEntity<ApiEnvelope<Policy>> publish(@PathVariable int year,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Publish body,Authentication auth,HttpServletRequest r){return ok(service.publish(year,body.draftId(),body.draftVersion(),actor(auth),key,meta(r)),r);}
    private static UUID actor(Authentication a){return UUID.fromString(a.getName());}
    private static Metadata meta(HttpServletRequest r){String ua=r.getHeader(HttpHeaders.USER_AGENT);if(ua!=null)ua=ua.replaceAll("[\\p{Cntrl}]","");if(ua!=null&&ua.length()>512)ua=ua.substring(0,512);return new Metadata(RequestIdFilter.get(r),r.getRemoteAddr(),ua);}
    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T value,HttpServletRequest r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(r)));}
    @ExceptionHandler(com.ramiart.admin.tuition.application.TuitionPolicyException.class) ResponseEntity<ApiEnvelope<Void>> error(com.ramiart.admin.tuition.application.TuitionPolicyException e,HttpServletRequest r){String c=e.code();int status=switch(c){case "TUITION_POLICY_NOT_FOUND","TUITION_POLICY_DRAFT_NOT_FOUND"->404;case "TUITION_POLICY_DRAFT_EXISTS","TUITION_POLICY_VERSION_CONFLICT","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->409;case "TUITION_POLICY_DUPLICATE_COUNT","TUITION_POLICY_NOT_PUBLISHABLE"->422;case "TUITION_POLICY_YEAR_INVALID","VALIDATION_ERROR"->400;default->500;};return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(c,"수업료 정책 요청을 처리할 수 없습니다.",List.of(),RequestIdFilter.get(r)));}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ApiEnvelope<Void>> persistence(DataAccessException e,HttpServletRequest r){LOGGER.error("Tuition policy persistence failed: requestId={}, method={}, path={}",RequestIdFilter.get(r),r.getMethod(),r.getRequestURI());return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure("TUITION_POLICY_SAVE_FAILED","수업료 정책 요청을 처리하지 못했습니다.",List.of(),RequestIdFilter.get(r)));}
}
