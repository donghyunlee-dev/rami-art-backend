package com.ramiart.admin.auth.api;

import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.AuthSessionService;
import com.ramiart.admin.auth.application.AuthSessionService.CurrentContext;
import com.ramiart.admin.auth.application.AuthSessionService.IssuedSession;
import com.ramiart.admin.auth.application.AuthSessionService.SessionContext;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth/sessions")
public class AuthSessionController {

    public static final String COOKIE_NAME = "__Host-rami_admin_session";

    private final AuthSessionService service;

    public AuthSessionController(AuthSessionService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiEnvelope<LoginResponse>> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request) {
        IssuedSession issued = service.login(
                body.email(), body.password(), body.returnUrl(), metadata(request));
        LoginResponse response = new LoginResponse(
                new LoginUser(issued.userId(), issued.displayName(), issued.role()),
                issued.passwordMustChange(),
                issued.expiresAt(),
                issued.redirectTo());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(issued.rawToken(), issued.expiresAt()))
                .cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(response, RequestIdFilter.get(request)));
    }

    @GetMapping("/current")
    ResponseEntity<ApiEnvelope<CurrentContext>> current(
            @CookieValue(name = COOKIE_NAME, required = false) String rawToken,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(service.current(rawToken), RequestIdFilter.get(request)));
    }

    @PatchMapping("/current")
    ResponseEntity<ApiEnvelope<SessionContext>> extend(
            @CookieValue(name = COOKIE_NAME, required = false) String rawToken,
            @Valid @RequestBody ExtendRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.success(
                        service.extend(rawToken, metadata(request)), RequestIdFilter.get(request)));
    }

    @DeleteMapping("/current")
    ResponseEntity<Void> logout(
            @CookieValue(name = COOKIE_NAME, required = false) String rawToken,
            HttpServletRequest request) {
        service.logout(rawToken, metadata(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie())
                .cacheControl(CacheControl.noStore())
                .build();
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

    private static String sessionCookie(String token, Instant expiresAt) {
        String cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .build()
                .toString();
        return cookie + "; Expires=" + formatCookieDate(expiresAt);
    }

    private static String expiredCookie() {
        String cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build()
                .toString();
        return cookie + "; Expires=" + formatCookieDate(Instant.EPOCH);
    }

    private static String formatCookieDate(Instant instant) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(instant, ZoneId.of("GMT")));
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 204) String returnUrl) {
    }

    public record ExtendRequest(
            @NotBlank @Pattern(regexp = "EXTEND", message = "EXTEND만 사용할 수 있습니다.") String action) {
    }

    public record LoginResponse(LoginUser user, boolean passwordMustChange, Instant expiresAt, String redirectTo) {
    }

    public record LoginUser(java.util.UUID id, String displayName, String role) {
    }
}
