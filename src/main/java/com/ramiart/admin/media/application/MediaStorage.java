package com.ramiart.admin.media.application;

public interface MediaStorage {
    void store(String key, byte[] content);

    void delete(String key);
}

