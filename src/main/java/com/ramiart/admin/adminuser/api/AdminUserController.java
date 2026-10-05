package com.ramiart.admin.adminuser.api;

import static com.ramiart.admin.adminuser.application.AdminUserModels.*;
import com.ramiart.admin.adminuser.application.AdminUserService;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public final class AdminUserController {
    private final AdminUserService service;
    public AdminUserController(AdminUserService service) { this.service = service; }

    @GetMapping
    ResponseEntity<ApiEnvelope<ListResponse>> list(@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String role, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="displayName,asc") String sort, Authentication auth, HttpServletRequest request) {
        var result = service.list(new Query(keyword, role, status, page, size, sort), auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result, RequestIdFilter.get(request)));
    }

    @PutMapping("/{userId}/role")
    ResponseEntity<ApiEnvelope<RoleChangeResponse>> changeRole(@PathVariable java.util.UUID userId,
            @RequestBody ChangeRoleRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) java.util.UUID key,
            Authentication auth, HttpServletRequest request) {
        var result = service.changeRole(userId, body, key, auth, new com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata(
                RequestIdFilter.get(request), request.getRemoteAddr(), userAgent(request)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result, RequestIdFilter.get(request)));
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<CreatedResponse>> create(@RequestBody CreateRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) java.util.UUID key,
            Authentication auth, HttpServletRequest request) {
        var result = service.create(body, key, auth, new com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata(
                RequestIdFilter.get(request), request.getRemoteAddr(), userAgent(request)));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result, RequestIdFilter.get(request)));
    }

    @PostMapping("/{userId}/status-changes")
    ResponseEntity<ApiEnvelope<StatusResponse>> changeStatus(@PathVariable java.util.UUID userId,
            @RequestBody StatusRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) java.util.UUID key,
            Authentication auth, HttpServletRequest request) {
        var result = service.changeStatus(userId,body,key,auth,new com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata(
                RequestIdFilter.get(request),request.getRemoteAddr(),userAgent(request)));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result,RequestIdFilter.get(request)));
    }

    @PostMapping("/{userId}/unlocking")
    ResponseEntity<ApiEnvelope<UnlockResponse>> unlock(@PathVariable java.util.UUID userId,
            @RequestBody VersionRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) java.util.UUID key,
            Authentication auth,HttpServletRequest request) {
        var result=service.unlock(userId,body,key,auth,new com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata(
                RequestIdFilter.get(request),request.getRemoteAddr(),userAgent(request)));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result,RequestIdFilter.get(request)));
    }

    @PostMapping("/{userId}/temporary-password-issuances")
    ResponseEntity<ApiEnvelope<TemporaryPasswordResponse>> issueTemporaryPassword(@PathVariable java.util.UUID userId,
            @RequestBody VersionRequest body,
            @RequestHeader(value="Idempotency-Key", required=false) java.util.UUID key,
            Authentication auth,HttpServletRequest request) {
        var result=service.issueTemporaryPassword(userId,body,key,auth,new com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata(
                RequestIdFilter.get(request),request.getRemoteAddr(),userAgent(request)));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result,RequestIdFilter.get(request)));
    }

    private static String userAgent(HttpServletRequest request) {
        String value=request.getHeader("User-Agent");
        if(value==null)return null;
        String sanitized=value.replaceAll("[\\p{Cntrl}]","");
        return sanitized.substring(0,Math.min(sanitized.length(),512));
    }
}
