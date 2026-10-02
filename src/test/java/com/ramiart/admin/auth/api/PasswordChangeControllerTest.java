package com.ramiart.admin.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.PasswordChangeService;
import com.ramiart.admin.auth.application.PasswordChangeService.Result;
import com.ramiart.admin.common.api.ApiEnvelope;
import com.ramiart.admin.common.api.RequestIdFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class PasswordChangeControllerTest {

    @Test
    void returnsRevokedSessionCountWithoutExposingPasswords() {
        PasswordChangeService service = mock(PasswordChangeService.class);
        PasswordChangeController controller = new PasswordChangeController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "req_password");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(HttpHeaders.USER_AGENT, "test-agent");
        RequestMetadata metadata = new RequestMetadata("req_password", "127.0.0.1", "test-agent");
        Instant changedAt = Instant.parse("2026-09-13T14:00:00Z");
        when(service.change(
                "raw-token", "current-password", "new-password-value", metadata))
                .thenReturn(new Result(2, changedAt));

        ResponseEntity<ApiEnvelope<Result>> response = controller.change(
                "raw-token",
                new PasswordChangeController.PasswordChangeRequest(
                        "current-password", "new-password-value"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().revokedSessionCount()).isEqualTo(2);
        assertThat(response.getBody().toString()).doesNotContain("current-password", "new-password-value");
    }
}
