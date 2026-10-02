package com.ramiart.admin.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordChangeRepository {

    Optional<PasswordAccount> findBySessionForUpdate(String tokenHash);

    List<String> findRecentPasswordHashes(UUID userId, int limit);

    boolean isCompromisedPassword(String passwordFingerprint);

    boolean changePassword(UUID userId, long version, String passwordHash, Instant changedAt);

    void addPasswordHistory(UUID userId, String passwordHash, Instant changedAt);

    void prunePasswordHistory(UUID userId, int retainedCount);

    int revokeOtherSessions(UUID userId, UUID currentSessionId, Instant revokedAt);

    void updateCurrentSessionActivity(UUID sessionId, Instant lastSeenAt, Instant idleExpiresAt);

    record PasswordAccount(
            UUID userId,
            UUID sessionId,
            String displayName,
            String status,
            String passwordHash,
            long version,
            int idleTimeoutMinutes,
            Instant expiresAt,
            Instant idleExpiresAt,
            Instant revokedAt) {
    }
}
