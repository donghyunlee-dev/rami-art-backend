package com.ramiart.admin.auth.application;

public interface AuthRateLimitStore {
    long consume(String bucketHash, int limit);
}
