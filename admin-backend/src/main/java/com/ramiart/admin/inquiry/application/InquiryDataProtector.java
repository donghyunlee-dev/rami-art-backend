package com.ramiart.admin.inquiry.application;

public interface InquiryDataProtector {
    byte[] protect(String value);
    String reveal(byte[] value);
    String hash(String value);
}
