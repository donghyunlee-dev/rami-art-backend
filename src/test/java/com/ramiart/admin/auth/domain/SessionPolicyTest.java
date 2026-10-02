package com.ramiart.admin.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionPolicyTest {

    private final SessionPolicy policy = new SessionPolicy(
            UUID.randomUUID(), 5, Duration.ofMinutes(15), Duration.ofMinutes(30),
            Duration.ofHours(8), Duration.ofMinutes(5));

    @Test
    void issuesIdleAndAbsoluteExpirationWindow() {
        Instant issuedAt = Instant.parse("2026-09-08T00:00:00Z");

        SessionPolicy.SessionWindow window = policy.issueAt(issuedAt);

        assertThat(window.idleExpiresAt()).isEqualTo(issuedAt.plus(Duration.ofMinutes(30)));
        assertThat(window.absoluteExpiresAt()).isEqualTo(issuedAt.plus(Duration.ofHours(8)));
        assertThat(window.warningAt()).isEqualTo(issuedAt.plus(Duration.ofMinutes(25)));
    }

    @Test
    void extensionNeverExceedsAbsoluteExpiration() {
        Instant now = Instant.parse("2026-09-08T07:50:00Z");
        Instant absoluteExpiresAt = Instant.parse("2026-09-08T08:00:00Z");

        SessionPolicy.SessionWindow window = policy.extendAt(now, absoluteExpiresAt);

        assertThat(window.idleExpiresAt()).isEqualTo(absoluteExpiresAt);
        assertThat(window.warningAt()).isEqualTo(Instant.parse("2026-09-08T07:55:00Z"));
    }

    @Test
    void rejectsExtensionAfterAbsoluteExpiration() {
        Instant now = Instant.parse("2026-09-08T08:00:00Z");

        assertThatThrownBy(() -> policy.extendAt(now, now))
                .isInstanceOf(IllegalStateException.class);
    }
}
