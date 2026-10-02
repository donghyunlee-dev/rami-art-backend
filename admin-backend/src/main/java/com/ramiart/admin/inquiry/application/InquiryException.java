package com.ramiart.admin.inquiry.application;

public final class InquiryException extends RuntimeException {
    private final String code;

    public InquiryException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
