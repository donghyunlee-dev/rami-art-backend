package com.ramiart.admin.auth.api;

import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.PasswordChangeService;
import com.ramiart.admin.auth.application.PasswordChangeService.Result;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users/me/password")
public class PasswordChangeController {

    private final PasswordChangeService service;

    public PasswordChangeController(PasswordChangeService service) {
        this.service = service;
    }

    @PutMapping
    ResponseEntity<ApiEnvelope<Result>> change(
            @CookieValue(name = AuthSessionController.COOKIE_NAME, required = false) String rawToken,
            @Valid @RequestBody PasswordChangeRequest body,
            HttpServletRequest request) {
        Result result = service.change(
                rawToken,
                body.currentPassword(),
                body.newPassword(),
                metadata(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(result, RequestIdFilter.get(request)));
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        return new RequestMetadata(
                RequestIdFilter.get(request),
                request.getRemoteAddr(),
                sanitizeUserAgent(request.getHeader(HttpHeaders.USER_AGENT)));
    }

    private static String sanitizeUserAgent(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "");
        return sanitized.substring(0, Math.min(sanitized.length(), 512));
    }

    public record PasswordChangeRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }
}
