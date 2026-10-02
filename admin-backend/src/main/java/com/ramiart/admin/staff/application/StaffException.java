package com.ramiart.admin.staff.application;

public final class StaffException extends RuntimeException {

    private final String code;

    public StaffException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
