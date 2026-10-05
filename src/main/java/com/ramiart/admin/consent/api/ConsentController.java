package com.ramiart.admin.consent.api;

import static com.ramiart.admin.consent.application.ConsentModels.*;

import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.consent.application.ConsentService;
import com.ramiart.admin.consent.application.ConsentService.ConsentException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public final class ConsentController {
    private final ConsentService service;
    public ConsentController(ConsentService service){this.service=service;}

    @GetMapping("/consent-policies")
    ResponseEntity<ApiEnvelope<List<Policy>>> policies(@RequestParam(required=false)String type,Authentication auth,HttpServletRequest request){return ok(service.policies(type,auth),request);}

    @PostMapping("/consent-policies/{type}/draft")
    ResponseEntity<ApiEnvelope<Policy>> createDraft(@PathVariable String type,@RequestBody PolicyDraft body,@RequestHeader("Idempotency-Key")UUID key,Authentication auth,HttpServletRequest request){
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.createDraft(type,body,key,auth,metadata(request)),RequestIdFilter.get(request)));
    }

    @PutMapping("/consent-policies/draft/{id}")
    ResponseEntity<ApiEnvelope<Policy>> updateDraft(@PathVariable UUID id,@RequestBody PolicyUpdate body,Authentication auth,HttpServletRequest request){
        PolicyDraft draft=body==null?null:new PolicyDraft(body.title(),body.body(),body.required(),body.validDays(),body.evidenceRequired());
        return ok(service.updateDraft(id,draft,body==null?-1:body.version(),auth,metadata(request)),request);
    }

    @PostMapping("/consent-policies/draft/{id}/publish")
    ResponseEntity<ApiEnvelope<Policy>> publish(@PathVariable UUID id,@RequestBody VersionRequest body,Authentication auth,HttpServletRequest request){if(body==null)throw new ConsentException("VALIDATION_ERROR");return ok(service.publish(id,body.version(),auth,metadata(request)),request);}

    @GetMapping("/students/{studentId}/consents")
    ResponseEntity<ApiEnvelope<StudentConsents>> studentConsents(@PathVariable UUID studentId,Authentication auth,HttpServletRequest request){return ok(service.studentConsents(studentId,auth),request);}

    @PostMapping("/students/{studentId}/consents")
    ResponseEntity<ApiEnvelope<StudentConsent>> collect(@PathVariable UUID studentId,@RequestBody CollectConsent body,@RequestHeader("Idempotency-Key")UUID key,Authentication auth,HttpServletRequest request){
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(service.collect(studentId,body,key,auth,metadata(request)),RequestIdFilter.get(request)));
    }

    @PostMapping("/student-consents/{id}/revocation")
    ResponseEntity<ApiEnvelope<StudentConsent>> revoke(@PathVariable UUID id,@RequestBody RevokeRequest body,@RequestHeader("Idempotency-Key")UUID key,Authentication auth,HttpServletRequest request){return ok(service.revoke(id,body,key,auth,metadata(request)),request);}

    @GetMapping("/student-consents/{id}/evidence-url")
    ResponseEntity<ApiEnvelope<EvidenceUrl>> evidenceUrl(@PathVariable UUID id,Authentication auth,HttpServletRequest request){return ok(service.evidenceUrl(id,auth),request);}

    private static <T>ResponseEntity<ApiEnvelope<T>>ok(T value,HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiEnvelope.success(value,RequestIdFilter.get(request)));}
    private static RequestMetadata metadata(HttpServletRequest r){String agent=r.getHeader(HttpHeaders.USER_AGENT);if(agent!=null)agent=agent.replaceAll("[\\p{Cntrl}]","");if(agent!=null&&agent.length()>512)agent=agent.substring(0,512);return new RequestMetadata(RequestIdFilter.get(r),r.getRemoteAddr(),agent);}
    @ExceptionHandler(ConsentException.class)
    ResponseEntity<ApiEnvelope<Void>> error(ConsentException exception,HttpServletRequest request){String code=exception.code();HttpStatus status=switch(code){
        case "CONSENT_READ_DENIED","CONSENT_WRITE_DENIED"->HttpStatus.FORBIDDEN;
        case "STUDENT_NOT_FOUND","CONSENT_NOT_FOUND","CONSENT_POLICY_NOT_FOUND"->HttpStatus.NOT_FOUND;
        case "CONSENT_EVIDENCE_NOT_FOUND"->HttpStatus.NOT_FOUND;
        case "CONSENT_POLICY_VERSION_CONFLICT","CONSENT_VERSION_CONFLICT","CONSENT_ACTIVE_EXISTS","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS"->HttpStatus.CONFLICT;
        case "CONSENT_POLICY_NOT_PUBLISHED","CONSENT_GUARDIAN_MISMATCH","CONSENT_EVIDENCE_REQUIRED","CONSENT_EVIDENCE_PRIVATE_REQUIRED"->HttpStatus.UNPROCESSABLE_ENTITY;
        case "CONSENT_DRAFT_EXISTS"->HttpStatus.CONFLICT;
        case "CONSENT_DRAFT_CREATE_FAILED"->HttpStatus.INTERNAL_SERVER_ERROR;
        case "CONSENT_EVIDENCE_STORAGE_UNAVAILABLE"->HttpStatus.SERVICE_UNAVAILABLE;
        default->HttpStatus.BAD_REQUEST;};
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiEnvelope.failure(code,"동의 정보를 처리하지 못했습니다.",List.of(),RequestIdFilter.get(request)));
    }
    public record PolicyUpdate(String title,String body,boolean required,Integer validDays,boolean evidenceRequired,long version){}
    public record VersionRequest(long version){}
}
