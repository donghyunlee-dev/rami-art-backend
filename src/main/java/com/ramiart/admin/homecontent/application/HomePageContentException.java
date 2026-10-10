package com.ramiart.admin.homecontent.application;

public final class HomePageContentException extends RuntimeException {
    private final String code;
    private final String field;
    public HomePageContentException(String code) { this(code, null); }
    public HomePageContentException(String code, String field) { super(code); this.code = code; this.field = field; }
    public String code() { return code; }
    public String field() { return field; }
}
