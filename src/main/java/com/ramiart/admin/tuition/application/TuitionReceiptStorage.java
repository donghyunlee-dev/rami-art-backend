package com.ramiart.admin.tuition.application;

public interface TuitionReceiptStorage {
    void upload(String key,byte[] pdf);
    byte[] download(String key);
    String signedUrl(String key,int expiresInSeconds);
    void delete(String key);
}
