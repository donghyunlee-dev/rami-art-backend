package com.ramiart.admin.sitebrand.application;
public final class SiteBrandException extends RuntimeException {
    private final String code;
    public SiteBrandException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
