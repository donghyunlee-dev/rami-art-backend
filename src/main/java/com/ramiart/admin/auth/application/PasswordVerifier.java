package com.ramiart.admin.auth.application;

public interface PasswordVerifier {

    boolean matches(String rawPassword, String encodedPassword);

    String encode(String rawPassword);

    String dummyHash();
}
