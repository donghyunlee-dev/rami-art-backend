package com.ramiart.admin.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramiart.admin.auth.application.PasswordChangeRepository.PasswordAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class AdminReauthenticationServiceTest {
    private final PasswordChangeRepository accounts=mock(PasswordChangeRepository.class);
    private final AdminReauthenticationRepository tokens=mock(AdminReauthenticationRepository.class);
    private final PasswordVerifier verifier=mock(PasswordVerifier.class);
    private final AuthRateLimiter limiter=mock(AuthRateLimiter.class);
    private final AuditRecorder audit=mock(AuditRecorder.class);
    private final Instant now=Instant.parse("2026-10-05T00:00:00Z");
    private final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    private final AdminReauthenticationService service=new AdminReauthenticationService(accounts,tokens,verifier,limiter,audit,clock);
    private final UUID userId=UUID.randomUUID(), sessionId=UUID.randomUUID();
    private final TestingAuthenticationToken auth=new TestingAuthenticationToken(userId.toString(),"","ADMIN_ACCOUNT_WRITE");

    @Test
    void issuesOpaqueFiveMinutePurposeBoundTokenOnlyAfterPasswordAndSessionVerification() {
        when(accounts.findBySessionForUpdate(any())).thenReturn(java.util.Optional.of(account()));
        when(verifier.matches("correct-password","encoded-hash")).thenReturn(true);

        var issued=service.issue("session-cookie","correct-password","MAKEUP_EXTENSION",auth,
                new AdminReauthenticationService.Metadata("request-reauth","127.0.0.1","test-agent"));

        assertThat(issued.reauthToken()).isNotBlank().doesNotContain("correct-password");
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(300));
        verify(tokens).issue(any(UUID.class),eq(userId),eq(sessionId),eq("MAKEUP_EXTENSION"),any(String.class),eq(issued.expiresAt()));
        verify(audit).record(any(AuditRecorder.Event.class));
    }

    @Test
    void wrongPasswordDoesNotIssueAToken() {
        when(accounts.findBySessionForUpdate(any())).thenReturn(java.util.Optional.of(account()));
        when(verifier.matches("wrong-password","encoded-hash")).thenReturn(false);

        assertThatThrownBy(()->service.issue("session-cookie","wrong-password","MAKEUP_EXTENSION",auth,
                new AdminReauthenticationService.Metadata("request-reauth","127.0.0.1","test-agent")))
                .isInstanceOf(AdminReauthenticationService.ReauthenticationException.class)
                .extracting("code").isEqualTo("REAUTHENTICATION_FAILED");
        verify(tokens,never()).issue(any(),any(),any(),any(),any(),any());
    }

    private PasswordAccount account(){return new PasswordAccount(userId,sessionId,"원장","ACTIVE","encoded-hash",0,30,
            now.plusSeconds(3600),now.plusSeconds(1800),null);}
}
