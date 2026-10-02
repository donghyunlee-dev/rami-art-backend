package com.ramiart.admin.student.application;

import java.util.Map;

public final class StudentException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    public StudentException(String code) { this(code, Map.of()); }

    public StudentException(String code, Map<String, Object> details) {
        super(code);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
