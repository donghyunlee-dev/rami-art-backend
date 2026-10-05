package com.ramiart.admin.auth.api;

import com.ramiart.admin.auth.application.SessionPolicyService;
import com.ramiart.admin.auth.application.SessionPolicyService.ChangeRequest;
import com.ramiart.admin.auth.application.SessionPolicyService.RequestMetadata;
import com.ramiart.admin.auth.application.SessionPolicyModels.Policy;
import com.ramiart.admin.auth.application.SessionPolicyModels.PolicyPage;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/session-policies")
public final class SessionPolicyController {
    private final SessionPolicyService service;
    public SessionPolicyController(SessionPolicyService service) { this.service = service; }

    @GetMapping
    ResponseEntity<ApiEnvelope<PolicyPage>> list(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size, Authentication authentication, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                ApiEnvelope.success(service.list(cursor, size, authentication), RequestIdFilter.get(request)));
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<Policy>> create(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody ChangeRequest body, Authentication authentication, HttpServletRequest request) {
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null) userAgent = userAgent.replaceAll("[\\p{Cntrl}]", "");
        RequestMetadata metadata = new RequestMetadata(RequestIdFilter.get(request), request.getRemoteAddr(),
                userAgent == null ? null : userAgent.substring(0, Math.min(512, userAgent.length())));
        Policy policy = service.create(body, idempotencyKey, metadata, authentication);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.LOCATION, "/api/admin/session-policies/" + policy.id())
                .body(ApiEnvelope.success(policy, RequestIdFilter.get(request)));
    }
}
