package com.ramiart.admin.privacy.api;

import static com.ramiart.admin.privacy.application.PublicPrivacyPolicyModels.*;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import com.ramiart.admin.privacy.application.PublicPrivacyPolicyException;
import com.ramiart.admin.privacy.application.PublicPrivacyPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PublicPrivacyPolicyController {
    private final PublicPrivacyPolicyService service;

    public PublicPrivacyPolicyController(PublicPrivacyPolicyService service) { this.service = service; }

    @GetMapping("/api/admin/public-privacy-policies")
    ResponseEntity<ApiEnvelope<Policies>> policies(Authentication auth, HttpServletRequest request) {
        return admin(service.policies(auth), request);
    }

    @PostMapping("/api/admin/public-privacy-policies/drafts")
    ResponseEntity<ApiEnvelope<Policy>> createDraft(@RequestHeader("Idempotency-Key") UUID key,
            Authentication auth, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.createDraft(key, auth, metadata(request)), RequestIdFilter.get(request)));
    }

    @PutMapping("/api/admin/public-privacy-policies/drafts/{id}")
    ResponseEntity<ApiEnvelope<Policy>> updateDraft(@PathVariable UUID id, @RequestBody Write body,
            Authentication auth, HttpServletRequest request) {
        return admin(service.updateDraft(id, body, auth, metadata(request)), request);
    }

    @GetMapping("/api/admin/public-privacy-policies/drafts/{id}/preview")
    ResponseEntity<ApiEnvelope<Preview>> preview(@PathVariable UUID id, Authentication auth, HttpServletRequest request) {
        return admin(service.preview(id, auth), request);
    }

    @PostMapping("/api/admin/public-privacy-policies/{id}/publication")
    ResponseEntity<ApiEnvelope<Policy>> publish(@PathVariable UUID id, @RequestBody PublicationRequest body,
            @RequestHeader("Idempotency-Key") UUID key, Authentication auth, HttpServletRequest request) {
        return admin(service.publish(id, body, key, auth, metadata(request)), request);
    }

    @GetMapping("/api/public/privacy-policy")
    ResponseEntity<ApiEnvelope<PublicView>> publicCurrent(HttpServletRequest request) {
        Policy policy = service.publicCurrent();
        PublicView view = new PublicView(policy.id(), policy.revision(), policy.versionCode(), policy.title(),
                policy.collectionItems(), policy.purpose(), policy.retentionMonths(), policy.retentionAnchor(),
                policy.contactEmail(), policy.effectiveOn(), policy.publishedAt());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .eTag("\"privacy-policy-" + policy.revision() + "\"")
                .body(ApiEnvelope.success(view, RequestIdFilter.get(request)));
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> admin(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(data, RequestIdFilter.get(request)));
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent != null) agent = agent.replaceAll("[\\p{Cntrl}]", "");
        if (agent != null && agent.length() > 512) agent = agent.substring(0, 512);
        return new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(), agent);
    }

    @ExceptionHandler(PublicPrivacyPolicyException.class)
    ResponseEntity<ApiEnvelope<Void>> error(PublicPrivacyPolicyException exception, HttpServletRequest request) {
        String code = exception.code();
        HttpStatus status = switch (code) {
            case "CONSENT_READ_DENIED", "CONSENT_WRITE_DENIED" -> HttpStatus.FORBIDDEN;
            case "PUBLIC_PRIVACY_POLICY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "PUBLIC_PRIVACY_POLICY_DRAFT_EXISTS", "PRIVACY_POLICY_VERSION_CONFLICT",
                    "PRIVACY_POLICY_RETENTION_REVIEW_REQUIRED", "IDEMPOTENCY_KEY_REUSED",
                    "IDEMPOTENCY_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "PRIVACY_POLICY_NOT_CURRENT" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.failure(code, "개인정보 안내 revision을 처리하지 못했습니다.", List.of(), RequestIdFilter.get(request)));
    }
}
