package com.ramiart.admin.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.PasswordChangeRepository.PasswordAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasswordChangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T14:00:00Z");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final RequestMetadata METADATA = new RequestMetadata("req_password", "127.0.0.1", "test-agent");

    private PasswordChangeRepository repository;
    private PasswordVerifier passwordVerifier;
    private AuditRecorder auditRecorder;
    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        repository = mock(PasswordChangeRepository.class);
        passwordVerifier = mock(PasswordVerifier.class);
        auditRecorder = mock(AuditRecorder.class);
        service = new PasswordChangeService(
                repository,
                passwordVerifier,
                auditRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC), mock(AuthRateLimiter.class));
    }

    @Test
    void changesPasswordAndRevokesOnlyOtherSessions() {
        PasswordAccount account = activeAccount();
        when(repository.findBySessionForUpdate(any())).thenReturn(Optional.of(account));
        when(passwordVerifier.matches("current-password", "stored-hash")).thenReturn(true);
        when(passwordVerifier.encode("new-password-value")).thenReturn("new-hash");
        when(repository.changePassword(USER_ID, 3, "new-hash", NOW)).thenReturn(true);
        when(repository.revokeOtherSessions(USER_ID, SESSION_ID, NOW)).thenReturn(2);

        PasswordChangeService.Result result = service.change(
                "raw-session-token", "current-password", "new-password-value", METADATA);

        assertThat(result.revokedSessionCount()).isEqualTo(2);
        assertThat(result.passwordChangedAt()).isEqualTo(NOW);
        verify(repository).addPasswordHistory(USER_ID, "stored-hash", NOW);
        verify(repository).prunePasswordHistory(USER_ID, 5);
        verify(repository).revokeOtherSessions(USER_ID, SESSION_ID, NOW);
        verify(repository).updateCurrentSessionActivity(
                SESSION_ID, NOW, NOW.plus(Duration.ofHours(1)));
        verify(auditRecorder).record(any(AuditRecorder.Event.class));
    }

    @Test
    void rejectsInvalidCurrentPasswordWithoutWriting() {
        when(repository.findBySessionForUpdate(any())).thenReturn(Optional.of(activeAccount()));
        when(passwordVerifier.matches("wrong-password", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.change(
                "raw-session-token", "wrong-password", "new-password-value", METADATA))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("CURRENT_PASSWORD_INVALID");

        verify(repository, never()).changePassword(any(), anyLong(), any(), any());
    }

    @Test
    void rejectsPasswordReuseAsPolicyViolation() {
        when(repository.findBySessionForUpdate(any())).thenReturn(Optional.of(activeAccount()));
        when(passwordVerifier.matches("same-password-value", "stored-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.change(
                "raw-session-token", "same-password-value", "same-password-value", METADATA))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_POLICY_VIOLATION");

        verify(passwordVerifier, never()).encode(any());
    }

    @Test
    void rejectsCompromisedPasswordBeforeEncoding() {
        when(repository.findBySessionForUpdate(any())).thenReturn(Optional.of(activeAccount()));
        when(passwordVerifier.matches("current-password", "stored-hash")).thenReturn(true);
        when(repository.isCompromisedPassword(any())).thenReturn(true);

        assertThatThrownBy(() -> service.change(
                "raw-session-token", "current-password", "password1234", METADATA))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_COMPROMISED");

        verify(passwordVerifier, never()).encode(any());
        verify(repository, never()).findRecentPasswordHashes(any(), anyInt());
    }

    @Test
    void rejectsRecentlyUsedPasswordBeforeEncoding() {
        when(repository.findBySessionForUpdate(any())).thenReturn(Optional.of(activeAccount()));
        when(passwordVerifier.matches("current-password", "stored-hash")).thenReturn(true);
        when(repository.findRecentPasswordHashes(USER_ID, 5)).thenReturn(List.of("previous-hash"));
        when(passwordVerifier.matches("previous-password", "previous-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.change(
                "raw-session-token", "current-password", "previous-password", METADATA))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_REUSED");

        verify(passwordVerifier, never()).encode(any());
    }

    @Test
    void reportsOptimisticLockConflict() {
        when(repository.findBySessionForUpdate(any())).thenReturn(Optional.of(activeAccount()));
        when(passwordVerifier.matches(any(), eq("stored-hash"))).thenReturn(true);
        when(passwordVerifier.encode(any())).thenReturn("new-hash");
        when(repository.changePassword(USER_ID, 3, "new-hash", NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.change(
                "raw-session-token", "current-password", "new-password-value", METADATA))
                .isInstanceOf(AuthSessionException.class)
                .extracting("code")
                .isEqualTo("ADMIN_USER_VERSION_CONFLICT");
    }

    private static PasswordAccount activeAccount() {
        return new PasswordAccount(
                USER_ID,
                SESSION_ID,
                "로컬 원장",
                "ACTIVE",
                "stored-hash",
                3,
                60,
                NOW.plus(Duration.ofHours(8)),
                NOW.plus(Duration.ofHours(1)),
                null);
    }
}
