package com.ramiart.admin.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuthSessionService;
import com.ramiart.admin.auth.application.AuthSessionService.IssuedSession;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthSessionControllerTest {

    private final AuthSessionService service = mock(AuthSessionService.class);
    private final AuthSessionController controller = new AuthSessionController(service);

    @Test
    void loginSetsHostOnlyStrictSecureCookieWithAbsoluteExpiry() {
        Instant expiresAt = Instant.parse("2026-09-13T12:00:00Z");
        MockHttpServletRequest request = request();
        when(service.login(
                "owner@rami.local", "correct-password", "/admin/dashboard",
                controllerMetadata(request)))
                .thenReturn(new IssuedSession(
                        UUID.randomUUID(), "raw-token", UUID.randomUUID(), "관리자", "OWNER",
                        false, expiresAt, "/admin/dashboard"));

        ResponseEntity<ApiEnvelope<AuthSessionController.LoginResponse>> response = controller.login(
                new AuthSessionController.LoginRequest(
                        "owner@rami.local", "correct-password", "/admin/dashboard"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("__Host-rami_admin_session=raw-token")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Expires=")
                .doesNotContain("Domain=");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().requestId()).isEqualTo("req_test");
    }

    @Test
    void logoutAlwaysExpiresTheBrowserCookie() {
        MockHttpServletRequest request = request();

        ResponseEntity<Void> response = controller.logout("raw-token", request);

        verify(service).logout("raw-token", controllerMetadata(request));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("Max-Age=0")
                .contains("Expires=");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "req_test");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(HttpHeaders.USER_AGENT, "test-agent");
        return request;
    }

    private static com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata controllerMetadata(
            MockHttpServletRequest request) {
        return new com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata(
                "req_test", request.getRemoteAddr(), "test-agent");
    }
}
