package com.ramiart.admin.auth.application;

import com.ramiart.admin.auth.domain.AdminAccount;
import com.ramiart.admin.auth.domain.EmailAddress;
import com.ramiart.admin.auth.domain.SessionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository {

    SessionPolicy findActivePolicy();

    Optional<LoginAccount> findAccountForLogin(EmailAddress email);

    boolean recordFailedLogin(UUID accountId, SessionPolicy policy, Instant now);

    UUID issueSession(
            LoginAccount account,
            SessionPolicy policy,
            String tokenHash,
            SessionPolicy.SessionWindow window,
            RequestMetadata metadata,
            Instant now);

    Optional<StoredSession> findSession(String tokenHash, boolean lockForUpdate);

    List<String> findPermissions(UUID roleId);

    void updateSessionActivity(UUID sessionId, Instant lastSeenAt, Instant idleExpiresAt);

    boolean revokeSession(UUID sessionId, Instant revokedAt);

    record LoginAccount(
            AdminAccount account,
            String passwordHash,
            UUID roleId,
            String roleCode) {
    }

    record StoredSession(
            UUID id,
            AdminAccount account,
            UUID roleId,
            String roleCode,
            SessionPolicy policy,
            Instant expiresAt,
            Instant idleExpiresAt,
            Instant revokedAt) {
    }

    record RequestMetadata(String requestId, String ipAddress, String userAgent) {
    }
}
