package com.ramiart.admin.consent.application;

public interface ConsentEvidenceStorage {
    String signedUrl(String storageKey,int expiresInSeconds);
}
