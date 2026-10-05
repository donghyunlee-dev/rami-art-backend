package com.ramiart.admin.datatransfer.application;

public interface DataTransferStorage {
    void upload(String key, byte[] file);
    void delete(String key);
}
