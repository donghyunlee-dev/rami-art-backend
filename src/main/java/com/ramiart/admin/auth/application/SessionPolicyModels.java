package com.ramiart.admin.auth.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class SessionPolicyModels {
    private SessionPolicyModels() {}
    public record Policy(UUID id, int version, int maxFailedAttempts, int lockDurationMinutes,
            int idleTimeoutMinutes, int absoluteTimeoutMinutes, int expiryWarningMinutes,
            String changeReason, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
            UUID createdById, String createdByName) {}
    public record PolicyPage(Policy current, List<Policy> history, Page page) {}
    public record Page(int size, String nextCursor) {}
}
