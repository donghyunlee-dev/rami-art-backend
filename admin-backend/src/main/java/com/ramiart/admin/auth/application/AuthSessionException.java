package com.ramiart.admin.auth.application;

public final class AuthSessionException extends RuntimeException {

    private final String code;

    public AuthSessionException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
