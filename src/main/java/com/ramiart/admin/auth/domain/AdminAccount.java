package com.ramiart.admin.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AdminAccount(
        UUID id,
        EmailAddress email,
        String displayName,
        AccountStatus status,
        Instant lockedUntil,
        boolean passwordMustChange,
        Instant temporaryPasswordExpiresAt) {

    public AdminAccount {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    public LoginAvailability loginAvailabilityAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status == AccountStatus.INACTIVE) {
            return LoginAvailability.INACTIVE;
        }
        if (status == AccountStatus.LOCKED && (lockedUntil == null || lockedUntil.isAfter(now))) {
            return LoginAvailability.LOCKED;
        }
        if (passwordMustChange && temporaryPasswordExpiresAt != null && !temporaryPasswordExpiresAt.isAfter(now)) {
            return LoginAvailability.TEMPORARY_PASSWORD_EXPIRED;
        }
        return LoginAvailability.ALLOWED;
    }

    public enum AccountStatus {
        ACTIVE,
        INACTIVE,
        LOCKED
    }

    public enum LoginAvailability {
        ALLOWED,
        INACTIVE,
        LOCKED,
        TEMPORARY_PASSWORD_EXPIRED
    }
}
