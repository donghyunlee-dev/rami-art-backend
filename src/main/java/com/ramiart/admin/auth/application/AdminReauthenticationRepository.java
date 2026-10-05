package com.ramiart.admin.auth.application;

import java.time.Instant;
import java.util.UUID;

public interface AdminReauthenticationRepository {
    void issue(UUID id, UUID adminUserId, UUID adminSessionId, String purpose, String tokenHash, Instant expiresAt);
    boolean consume(String tokenHash, UUID adminUserId, UUID adminSessionId, String purpose, Instant now);
}
