package com.ramiart.admin.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuthSessionRepository.LoginAccount;
import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.AuthSessionRepository.StoredSession;
import com.ramiart.admin.auth.domain.AdminAccount;
import com.ramiart.admin.auth.domain.EmailAddress;
import com.ramiart.admin.auth.domain.SessionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T01:00:00Z");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ROLE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final RequestMetadata METADATA = new RequestMetadata("req_test", "127.0.0.1", "test-agent");

    private AuthSessionRepository repository;
    private AuditRecorder auditRecorder;
    private PasswordVerifier passwordVerifier;
    private AuthSessionService service;
    private SessionPolicy policy;

    @BeforeEach
    void setUp() {
        repository = mock(AuthSessionRepository.class);
        auditRecorder = mock(AuditRecorder.class);
        passwordVerifier = mock(PasswordVerifier.class);
        service = new AuthSessionService(
                repository,
                auditRecorder,
                passwordVerifier,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(AuthRateLimiter.class));
        policy = new SessionPolicy(
                UUID.randomUUID(), 5, Duration.ofMinutes(30), Duration.ofMinutes(60),
                Duration.ofHours(12), Duration.ofMinutes(5));
    }

    @Test
    void createsAHashedServerSessionAndRejectsExternalReturnUrl() {
        LoginAccount loginAccount = loginAccount(false);
        when(repository.findActivePolicy()).thenReturn(policy);
        when(repository.findAccountForLogin(EmailAddress.of("owner@rami.local")))
                .thenReturn(Optional.of(loginAccount));
        when(passwordVerifier.matches("correct-password", "stored-hash")).thenReturn(true);
        when(repository.issueSession(eq(loginAccount), eq(policy), any(), any(), eq(METADATA), eq(NOW)))
                .thenReturn(SESSION_ID);

        AuthSessionService.IssuedSession result = service.login(
                " OWNER@RAMI.LOCAL ", "correct-password", "https://evil.example/admin", METADATA);

        assertThat(result.rawToken()).hasSize(43);
        assertThat(result.redirectTo()).isEqualTo("/admin/dashboard");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        verify(repository).issueSession(
                eq(loginAccount), eq(policy), eq(com.ramiart.admin.auth.domain.SessionToken.sha256(result.rawToken())),
                any(), eq(METADATA), eq(NOW));
        verify(auditRecorder).record(any(AuditRecorder.Event.class));
    }

    @Test
    void performsDummyPasswordCheckForUnknownAccount() {
        when(repository.findActivePolicy()).thenReturn(policy);
        when(repository.findAccountForLogin(any())).thenReturn(Optional.empty());
        when(passwordVerifier.dummyHash()).thenReturn("dummy-hash");

        assertThatThrownBy(() -> service.login(
                "missing@rami.local", "wrong-password", null, METADATA))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("AUTHENTICATION_FAILED");

        verify(passwordVerifier).matches("wrong-password", "dummy-hash");
        verify(auditRecorder).record(any(AuditRecorder.Event.class));
    }

    @Test
    void returnsFirstAllowedRouteUsingDocumentedPriority() {
        AdminAccount account = account(false);
        StoredSession stored = new StoredSession(
                SESSION_ID, account, ROLE_ID, "OPERATOR", policy,
                NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofHours(1)), null);
        when(repository.findSession(any(), eq(false))).thenReturn(Optional.of(stored));
        when(repository.findPermissions(ROLE_ID)).thenReturn(List.of("STUDENT_READ", "SCHEDULE_READ"));

        AuthSessionService.CurrentContext result = service.current("raw-session-token");

        assertThat(result.firstAllowedPath()).isEqualTo("/admin/students");
        assertThat(result.session().warningAt()).isEqualTo(NOW.plus(Duration.ofMinutes(55)));
    }

    @Test
    void routesSiteBrandOnlyAdministratorsToTheBrandSettingsScreen() {
        AdminAccount account = account(false);
        StoredSession stored = new StoredSession(
                SESSION_ID, account, ROLE_ID, "CONTENT", policy,
                NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofHours(1)), null);
        when(repository.findSession(any(), eq(false))).thenReturn(Optional.of(stored));
        when(repository.findPermissions(ROLE_ID)).thenReturn(List.of("SITE_BRAND_READ"));

        AuthSessionService.CurrentContext result = service.current("raw-session-token");

        assertThat(result.firstAllowedPath()).isEqualTo("/admin/settings/brand");
    }

    @Test
    void forcesPasswordChangeRouteWhileKeepingRolePermissions() {
        AdminAccount account = account(true);
        StoredSession stored = new StoredSession(
                SESSION_ID, account, ROLE_ID, "OWNER", policy,
                NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofHours(1)), null);
        when(repository.findSession(any(), eq(false))).thenReturn(Optional.of(stored));
        when(repository.findPermissions(ROLE_ID)).thenReturn(List.of("DASHBOARD_READ"));

        AuthSessionService.CurrentContext result = service.current("raw-session-token");

        assertThat(result.firstAllowedPath()).isEqualTo("/admin/settings/password?required=true");
        assertThat(result.user().permissions()).containsExactly("DASHBOARD_READ");
    }

    private LoginAccount loginAccount(boolean passwordMustChange) {
        return new LoginAccount(account(passwordMustChange), "stored-hash", ROLE_ID, "OWNER");
    }

    private AdminAccount account(boolean passwordMustChange) {
        return new AdminAccount(
                USER_ID,
                EmailAddress.of("owner@rami.local"),
                "로컬 원장",
                AdminAccount.AccountStatus.ACTIVE,
                null,
                passwordMustChange,
                passwordMustChange ? NOW.plus(Duration.ofHours(1)) : null);
    }
}
