package com.ramiart.admin.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminAccountTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void blocksAnAccountWhileLockIsActive() {
        AdminAccount account = account(
                AdminAccount.AccountStatus.LOCKED,
                NOW.plusSeconds(60),
                false,
                null);

        assertThat(account.loginAvailabilityAt(NOW))
                .isEqualTo(AdminAccount.LoginAvailability.LOCKED);
    }

    @Test
    void allowsLoginAfterTemporaryLockExpires() {
        AdminAccount account = account(
                AdminAccount.AccountStatus.LOCKED,
                NOW.minusSeconds(1),
                false,
                null);

        assertThat(account.loginAvailabilityAt(NOW))
                .isEqualTo(AdminAccount.LoginAvailability.ALLOWED);
    }

    @Test
    void rejectsAnExpiredTemporaryPassword() {
        AdminAccount account = account(
                AdminAccount.AccountStatus.ACTIVE,
                null,
                true,
                NOW);

        assertThat(account.loginAvailabilityAt(NOW))
                .isEqualTo(AdminAccount.LoginAvailability.TEMPORARY_PASSWORD_EXPIRED);
    }

    private static AdminAccount account(
            AdminAccount.AccountStatus status,
            Instant lockedUntil,
            boolean passwordMustChange,
            Instant temporaryPasswordExpiresAt) {
        return new AdminAccount(
                UUID.randomUUID(),
                EmailAddress.of("owner@rami.local"),
                "로컬 원장",
                status,
                lockedUntil,
                passwordMustChange,
                temporaryPasswordExpiresAt);
    }
}
