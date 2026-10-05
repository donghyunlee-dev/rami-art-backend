package com.ramiart.admin.audit.api;

import com.ramiart.admin.audit.application.AuditModels.Detail;
import com.ramiart.admin.audit.application.AuditModels.Options;
import com.ramiart.admin.audit.application.AuditModels.ListResponse;
import com.ramiart.admin.audit.application.AuditModels.Query;
import com.ramiart.admin.audit.application.AuditService;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public final class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) { this.service = service; }

    @GetMapping("/audit-log-options")
    ResponseEntity<ApiEnvelope<Options>> options(Authentication authentication, HttpServletRequest request) {
        return ok(service.options(authentication), request);
    }

    @GetMapping("/audit-logs")
    ResponseEntity<ApiEnvelope<ListResponse>> list(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String actorType,
            @RequestParam(required = false) UUID actorId, @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String action, @RequestParam(required = false) String result,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int size,
            Authentication authentication, HttpServletRequest request) {
        Query query = new Query(from, to, actorType, actorId, taskId, action, result, cursor, size);
        return ok(service.list(query, authentication), request);
    }

    @GetMapping("/audit-logs/{auditLogId}")
    ResponseEntity<ApiEnvelope<Detail>> detail(@PathVariable UUID auditLogId, Authentication authentication,
            HttpServletRequest request) {
        return ok(service.detail(auditLogId, authentication), request);
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(data, RequestIdFilter.get(request)));
    }
}
