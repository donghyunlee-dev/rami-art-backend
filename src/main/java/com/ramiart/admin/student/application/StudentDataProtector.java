package com.ramiart.admin.student.application;

public interface StudentDataProtector {
    record ProtectedValue(byte[] ciphertext, String hash) {}
    ProtectedValue protect(String normalizedValue);
    String reveal(byte[] ciphertext);
    String hash(String normalizedValue);
}
