package com.ramiart.admin.tuition.application;

public interface TuitionPreviewSnapshotProtector {
    byte[] encrypt(String value);
    String decrypt(byte[] value);
}
