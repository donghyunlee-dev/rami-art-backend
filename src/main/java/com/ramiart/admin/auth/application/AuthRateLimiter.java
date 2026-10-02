package com.ramiart.admin.auth.application;

import com.ramiart.admin.auth.domain.SessionToken;
import org.springframework.stereotype.Service;

@Service
public class AuthRateLimiter {
    private final AuthRateLimitStore store;

    public AuthRateLimiter(AuthRateLimitStore store) {
        this.store = store;
    }

    public void check(String scope, String identity, int limit) {
        long retryAfter = store.consume(SessionToken.sha256(scope + ":" + identity), limit);
        if (retryAfter > 0) throw new AuthRateLimitException(
                scope.startsWith("password-") ? "PASSWORD_CHANGE_RATE_LIMITED" : "TOO_MANY_REQUESTS", retryAfter);
    }
}
