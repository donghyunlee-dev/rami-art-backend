package com.ramiart.admin.media.application;

public interface MediaStorage {
    void store(String key, byte[] content, String contentType);

    void delete(String key);
    String publicUrl(String key);
}

