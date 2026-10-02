package com.ramiart.admin.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SessionPolicy(
        UUID id,
        int failedLoginThreshold,
        Duration lockDuration,
        Duration idleTimeout,
        Duration absoluteTimeout,
        Duration warningLeadTime) {

    public SessionPolicy {
        Objects.requireNonNull(id, "id must not be null");
        requirePositive(failedLoginThreshold, "failedLoginThreshold");
        requirePositive(lockDuration, "lockDuration");
        requirePositive(idleTimeout, "idleTimeout");
        requirePositive(absoluteTimeout, "absoluteTimeout");
        requirePositive(warningLeadTime, "warningLeadTime");
        if (idleTimeout.compareTo(absoluteTimeout) > 0) {
            throw new IllegalArgumentException("idleTimeout must not exceed absoluteTimeout");
        }
        if (warningLeadTime.compareTo(idleTimeout) >= 0) {
            throw new IllegalArgumentException("warningLeadTime must be shorter than idleTimeout");
        }
    }

    public SessionWindow issueAt(Instant issuedAt) {
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Instant absoluteExpiresAt = issuedAt.plus(absoluteTimeout);
        Instant idleExpiresAt = earlierOf(issuedAt.plus(idleTimeout), absoluteExpiresAt);
        return new SessionWindow(absoluteExpiresAt, idleExpiresAt, idleExpiresAt.minus(warningLeadTime));
    }

    public SessionWindow extendAt(Instant now, Instant absoluteExpiresAt) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt must not be null");
        if (!absoluteExpiresAt.isAfter(now)) {
            throw new IllegalStateException("absolute session has expired");
        }
        Instant idleExpiresAt = earlierOf(now.plus(idleTimeout), absoluteExpiresAt);
        Instant warningAt = idleExpiresAt.minus(warningLeadTime);
        return new SessionWindow(absoluteExpiresAt, idleExpiresAt, warningAt.isBefore(now) ? now : warningAt);
    }

    private static Instant earlierOf(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public record SessionWindow(Instant absoluteExpiresAt, Instant idleExpiresAt, Instant warningAt) {
    }
}
