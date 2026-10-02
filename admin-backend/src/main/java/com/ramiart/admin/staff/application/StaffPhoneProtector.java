package com.ramiart.admin.staff.application;

public interface StaffPhoneProtector {

    ProtectedPhone protect(String normalizedPhone);

    String reveal(byte[] ciphertext);

    record ProtectedPhone(byte[] ciphertext, String hash, String last4) {
    }
}
