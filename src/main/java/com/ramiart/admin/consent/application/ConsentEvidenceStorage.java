package com.ramiart.admin.consent.application;

public interface ConsentEvidenceStorage {
    void upload(String storageKey,byte[] bytes,String contentType);
    void delete(String storageKey);
    String signedUrl(String storageKey,int expiresInSeconds);
}
