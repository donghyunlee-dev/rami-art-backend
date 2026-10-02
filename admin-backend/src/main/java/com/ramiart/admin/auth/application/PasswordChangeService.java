package com.ramiart.admin.auth.application;

import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.auth.application.PasswordChangeRepository.PasswordAccount;
import com.ramiart.admin.auth.domain.SessionToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordChangeService {

    private static final int RETAINED_PASSWORD_COUNT = 5;

    private final PasswordChangeRepository repository;
    private final PasswordVerifier passwordVerifier;
    private final AuditRecorder auditRecorder;
    private final Clock clock;
    private final AuthRateLimiter rateLimiter;

    public PasswordChangeService(
            PasswordChangeRepository repository,
            PasswordVerifier passwordVerifier,
            AuditRecorder auditRecorder,
            Clock clock,
            AuthRateLimiter rateLimiter) {
        this.repository = repository;
        this.passwordVerifier = passwordVerifier;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public Result change(
            String rawToken,
            String currentPassword,
            String newPassword,
            RequestMetadata metadata) {
        rateLimiter.check("password-ip", metadata.ipAddress(), 30);
        PasswordAccount account = requireActiveAccount(rawToken);
        rateLimiter.check("password-user", account.userId().toString(), 5);
        if (!passwordVerifier.matches(currentPassword, account.passwordHash())) {
            throw new AuthSessionException("CURRENT_PASSWORD_INVALID");
        }
        validateNewPassword(currentPassword, newPassword);
        validatePasswordRisk(account, newPassword);

        Instant now = clock.instant();
        String newPasswordHash = passwordVerifier.encode(newPassword);
        if (!repository.changePassword(account.userId(), account.version(), newPasswordHash, now)) {
            throw new AuthSessionException("ADMIN_USER_VERSION_CONFLICT");
        }
        repository.addPasswordHistory(account.userId(), account.passwordHash(), now);
        repository.prunePasswordHistory(account.userId(), RETAINED_PASSWORD_COUNT);
        int revokedSessionCount = repository.revokeOtherSessions(
                account.userId(), account.sessionId(), now);
        Instant idleExpiresAt = now.plusSeconds(account.idleTimeoutMinutes() * 60L);
        if (idleExpiresAt.isAfter(account.expiresAt())) {
            idleExpiresAt = account.expiresAt();
        }
        repository.updateCurrentSessionActivity(account.sessionId(), now, idleExpiresAt);
        auditRecorder.record(new AuditRecorder.Event(
                now,
                metadata.requestId(),
                "MGT-ADMIN-PASSWORD",
                "SECURITY",
                "ADMIN",
                account.userId(),
                account.displayName(),
                "ADMIN_PASSWORD_CHANGED",
                "ADMIN_USER",
                account.userId(),
                "SUCCESS",
                null,
                metadata.ipAddress(),
                metadata.userAgent(),
                Map.of("revokedSessionCount", revokedSessionCount)));
        return new Result(revokedSessionCount, now);
    }

    private PasswordAccount requireActiveAccount(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthSessionException("SESSION_REQUIRED");
        }
        PasswordAccount account = repository.findBySessionForUpdate(SessionToken.sha256(rawToken))
                .orElseThrow(() -> new AuthSessionException("SESSION_EXPIRED"));
        Instant now = clock.instant();
        if (account.revokedAt() != null) {
            throw new AuthSessionException("SESSION_REVOKED");
        }
        if (!account.expiresAt().isAfter(now) || !account.idleExpiresAt().isAfter(now)) {
            throw new AuthSessionException("SESSION_EXPIRED");
        }
        if (!"ACTIVE".equals(account.status())) {
            throw new AuthSessionException("SESSION_REVOKED");
        }
        return account;
    }

    private static void validateNewPassword(String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 128
                || newPassword.equals(currentPassword)) {
            throw new AuthSessionException("PASSWORD_POLICY_VIOLATION");
        }
    }

    private void validatePasswordRisk(PasswordAccount account, String newPassword) {
        if (repository.isCompromisedPassword(SessionToken.sha256(newPassword))) {
            throw new AuthSessionException("PASSWORD_COMPROMISED");
        }
        boolean reused = repository.findRecentPasswordHashes(account.userId(), RETAINED_PASSWORD_COUNT).stream()
                .anyMatch(passwordHash -> passwordVerifier.matches(newPassword, passwordHash));
        if (reused) {
            throw new AuthSessionException("PASSWORD_REUSED");
        }
    }

    public record Result(int revokedSessionCount, Instant passwordChangedAt) {
    }
}
