package com.ramiart.admin.auth.application;

public class AuthRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public AuthRateLimitException(String code, long retryAfterSeconds) {
        super(code);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
