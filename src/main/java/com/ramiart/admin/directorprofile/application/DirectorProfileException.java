package com.ramiart.admin.directorprofile.application;

public final class DirectorProfileException extends RuntimeException {
    private final String code;
    private final String field;
    public DirectorProfileException(String code) { this(code, null); }
    public DirectorProfileException(String code, String field) { super(code); this.code = code; this.field = field; }
    public String code() { return code; }
    public String field() { return field; }
}
