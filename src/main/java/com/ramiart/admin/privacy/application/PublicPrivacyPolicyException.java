package com.ramiart.admin.privacy.application;

public final class PublicPrivacyPolicyException extends RuntimeException {
    private final String code;
    public PublicPrivacyPolicyException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
