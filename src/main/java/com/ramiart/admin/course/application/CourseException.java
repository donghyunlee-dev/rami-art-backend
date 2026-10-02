package com.ramiart.admin.course.application;

public final class CourseException extends RuntimeException {

    private final String code;

    public CourseException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
