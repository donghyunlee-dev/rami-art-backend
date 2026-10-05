package com.ramiart.admin.classprogram.application;

public final class ClassProgramException extends RuntimeException {
    private final String code;
    private final String field;
    public ClassProgramException(String code) { this(code, null); }
    public ClassProgramException(String code, String field) { super(code); this.code = code; this.field = field; }
    public String code() { return code; }
    public String field() { return field; }
}
