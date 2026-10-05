package com.ramiart.admin.studioprofile.application;

public final class StudioProfileException extends RuntimeException {
    private final String code;
    private final String field;

    public StudioProfileException(String code) { this(code, null); }
    public StudioProfileException(String code, String field) {
        super(code);
        this.code = code;
        this.field = field;
    }
    public String code() { return code; }
    public String field() { return field; }
}
