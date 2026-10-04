package com.ramiart.admin.tuition.application;
public final class TuitionPolicyException extends RuntimeException {
    private final String code;
    public TuitionPolicyException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
