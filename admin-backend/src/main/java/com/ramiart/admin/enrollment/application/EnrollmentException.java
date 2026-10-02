package com.ramiart.admin.enrollment.application;

import java.util.Map;

public final class EnrollmentException extends RuntimeException {
    private final String code;
    private final Map<String,Object> details;
    public EnrollmentException(String code) { this(code, Map.of()); }
    public EnrollmentException(String code, Map<String,Object> details) {
        super(code); this.code=code; this.details=Map.copyOf(details);
    }
    public String code(){ return code; }
    public Map<String,Object> details(){ return details; }
}
