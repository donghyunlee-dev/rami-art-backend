package com.ramiart.admin.tuition.application;
import java.util.Map;
public final class StudentTuitionAssignmentException extends RuntimeException {
    private final String code;
    private final Map<String,Object> details;
    public StudentTuitionAssignmentException(String code){this(code,Map.of());}
    public StudentTuitionAssignmentException(String code,Map<String,Object> details){super(code);this.code=code;this.details=Map.copyOf(details);}
    public String code(){return code;}
    public Map<String,Object> details(){return details;}
}
